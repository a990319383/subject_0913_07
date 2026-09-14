package com.evops.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.common.BusinessException;
import com.evops.constant.TvacConst;
import com.evops.dto.FramePieceRequest;
import com.evops.dto.FrameSessionStartRequest;
import com.evops.entity.TvacChannel;
import com.evops.entity.TvacFramePiece;
import com.evops.entity.TvacFrameSession;
import com.evops.entity.TvacObservation;
import com.evops.entity.TvacPlan;
import com.evops.mapper.TvacChannelMapper;
import com.evops.mapper.TvacFramePieceMapper;
import com.evops.mapper.TvacFrameSessionMapper;
import com.evops.mapper.TvacPlanMapper;
import com.evops.util.Checksums;
import com.evops.vo.FrameAssemblyVo;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 地面站二进制解码结果分片重组。
 *
 * <p>地面站会把一个带帧号的逻辑帧按通道拆成分片重复发送，本服务保证：
 * <ul>
 *   <li><b>乱序帧</b>：按 (会话, 通道, 片序号) 去重落库，receive_seq 记录实际到达顺序，
 *       收齐（或显式 assemble）后统一按片序重组，不依赖到达顺序；</li>
 *   <li><b>分片校验和</b>：每片到片即复算 CRC32，与客户端校验和比对；</li>
 *   <li><b>重复发送</b>：同 (通道, 片序) 同报文幂等丢弃；曾被判坏的片可用正确报文重发修复；</li>
 *   <li><b>单通道坏帧隔离</b>：CRC 不符 / 通道停用 / 归属错误的片标记 BAD 隔离，
 *       不影响其他通道片重组落帧。</li>
 * </ul>
 */
@Service
public class FrameReassemblyService {

    private final TvacFrameSessionMapper sessionMapper;
    private final TvacFramePieceMapper pieceMapper;
    private final TvacPlanMapper planMapper;
    private final TvacChannelMapper channelMapper;
    private final ObservationProjector projector;
    private final ObservationLockGuard lockGuard;

    public FrameReassemblyService(TvacFrameSessionMapper sessionMapper,
                                  TvacFramePieceMapper pieceMapper,
                                  TvacPlanMapper planMapper,
                                  TvacChannelMapper channelMapper,
                                  ObservationProjector projector,
                                  ObservationLockGuard lockGuard) {
        this.sessionMapper = sessionMapper;
        this.pieceMapper = pieceMapper;
        this.planMapper = planMapper;
        this.channelMapper = channelMapper;
        this.projector = projector;
        this.lockGuard = lockGuard;
    }

    /** 开始（或幂等复用）一次重组会话 */
    @Transactional(rollbackFor = Exception.class)
    public TvacFrameSession startSession(FrameSessionStartRequest req) {
        TvacPlan plan = planMapper.selectOne(
                new QueryWrapper<TvacPlan>().eq("plan_code", req.getPlanCode()));
        if (plan == null) {
            throw BusinessException.of("计划编号不存在: " + req.getPlanCode());
        }
        if (!TvacConst.PlanStatus.RUNNING.equals(plan.getStatus())) {
            throw BusinessException.of("只有进行中的计划才能接收二进制解码分片，当前状态: "
                    + plan.getStatus());
        }
        String key = req.getSessionKey() == null || req.getSessionKey().trim().isEmpty()
                ? req.getPlanCode() + "#" + req.getFrameNo() : req.getSessionKey().trim();

        TvacFrameSession existing = sessionMapper.selectOne(
                new QueryWrapper<TvacFrameSession>().eq("session_key", key));
        if (existing != null) {
            return existing;
        }
        TvacFrameSession session = new TvacFrameSession();
        session.setSessionKey(key);
        session.setFrameNo(req.getFrameNo());
        session.setPlanId(plan.getId());
        session.setArticleId(plan.getArticleId());
        session.setExpectedPieces(req.getExpectedPieces());
        session.setGoodPieces(0);
        session.setBadPieces(0);
        session.setSourceDevice(req.getSourceDevice());
        session.setStatus("ASSEMBLING");
        sessionMapper.insert(session);
        return session;
    }

    /**
     * 接收一个通道分片。返回本次到片判定（VERIFIED/BAD/DUPLICATED），
     * 收齐预期片数时自动触发重组。
     */
    @Transactional(rollbackFor = Exception.class)
    public FrameAssemblyVo receivePiece(FramePieceRequest req) {
        TvacFrameSession session = sessionMapper.selectOne(
                new QueryWrapper<TvacFrameSession>().eq("session_key", req.getSessionKey()));
        if (session == null) {
            throw BusinessException.of("重组会话不存在: " + req.getSessionKey());
        }
        TvacPlan plan = planMapper.selectById(session.getPlanId());
        TvacChannel channel = channelMapper.selectOne(
                new QueryWrapper<TvacChannel>().eq("channel_code", req.getChannelCode()));

        String serverChecksum = Checksums.crc32Hex(req.getPayload());
        boolean crcOk = req.getClientChecksum() == null || req.getClientChecksum().trim().isEmpty()
                || Checksums.matches(req.getClientChecksum(), serverChecksum);

        FrameAssemblyVo vo = newVo(session, plan == null ? null : plan.getPlanCode());

        TvacFramePiece existing = pieceMapper.selectOne(new QueryWrapper<TvacFramePiece>()
                .eq("session_id", session.getId())
                .eq("channel_id", channel == null ? -1L : channel.getId())
                .eq("piece_seq", req.getPieceSeq()));

        // 通道在主数据中不存在：协议级坏片，无法落库（channel_id 外键），直接隔离返回
        if (channel == null) {
            return rejectPiece(vo, req, "通道编号不存在: " + req.getChannelCode());
        }

        if (existing != null) {
            return handleDuplicate(session, existing, req, serverChecksum, crcOk, vo, plan, channel);
        }

        boolean ownershipOk = channel.getArticleId().equals(session.getArticleId());
        boolean enabled = !TvacConst.ChannelStatus.DISABLED.equals(channel.getStatus());
        String badReason = null;
        if (!crcOk) {
            badReason = "分片校验和不符：客户端 " + req.getClientChecksum()
                    + "，服务端复算 " + serverChecksum;
        } else if (!ownershipOk) {
            badReason = "通道 " + channel.getChannelCode() + " 不属于本计划的试验件，坏帧隔离";
        } else if (!enabled) {
            badReason = "通道 " + channel.getChannelCode() + " 已停用，坏帧隔离";
        }

        int receiveSeq = pieceMapper.selectCount(
                new QueryWrapper<TvacFramePiece>().eq("session_id", session.getId())).intValue() + 1;
        TvacFramePiece piece = new TvacFramePiece();
        piece.setSessionId(session.getId());
        piece.setChannelId(channel.getId());
        piece.setPieceSeq(req.getPieceSeq());
        piece.setReceiveSeq(receiveSeq);
        piece.setPayload(req.getPayload());
        piece.setRawValue(req.getRawValue());
        piece.setEngValue(req.getEngValue());
        piece.setCycleNo(req.getCycleNo());
        piece.setFrameTime(req.getFrameTime());
        piece.setClientChecksum(req.getClientChecksum());
        piece.setServerChecksum(serverChecksum);
        if (badReason == null) {
            piece.setStatus(TvacConst.PieceStatus.VERIFIED);
        } else {
            piece.setStatus(TvacConst.PieceStatus.BAD);
            piece.setBadReason(badReason);
        }
        pieceMapper.insert(piece);
        recountSession(session);

        vo.setPieceSeq(req.getPieceSeq());
        vo.setChannelCode(channel.getChannelCode());
        if (badReason == null) {
            vo.setPieceStatus(TvacConst.PieceStatus.VERIFIED);
        } else {
            vo.setPieceStatus(TvacConst.PieceStatus.BAD);
            vo.setBadReason(badReason);
            vo.getBadPieceList().add(bad(channel.getChannelCode(), req.getPieceSeq(), badReason));
        }

        return maybeAutoAssemble(session, vo);
    }

    /** 重复片处理：同报文幂等丢弃；坏片可用正确报文重发修复；好片冲突报文判坏 */
    private FrameAssemblyVo handleDuplicate(TvacFrameSession session, TvacFramePiece existing,
                                            FramePieceRequest req, String serverChecksum,
                                            boolean crcOk, FrameAssemblyVo vo,
                                            TvacPlan plan, TvacChannel channel) {
        vo.setPieceSeq(req.getPieceSeq());
        vo.setChannelCode(channel.getChannelCode());
        boolean samePayload = Checksums.matches(existing.getServerChecksum(), serverChecksum);

        if (samePayload && crcOk) {
            // 地面站重发同一片：幂等，直接返回首片判定，不重复落帧
            vo.setPieceStatus(TvacConst.PieceStatus.DUPLICATED);
            vo.setBadReason(TvacConst.PieceStatus.VERIFIED.equals(existing.getStatus())
                    ? "重复片，校验一致，幂等丢弃" : existing.getBadReason());
            fillCounts(session, vo);
            return vo;
        }
        // 曾被判坏的片，本次以校验通过的正确报文重发 -> 修复坏片（地面站重传纠错）
        if (!samePayload && crcOk && TvacConst.PieceStatus.BAD.equals(existing.getStatus())) {
            existing.setStatus(TvacConst.PieceStatus.VERIFIED);
            existing.setBadReason(null);
            existing.setClientChecksum(req.getClientChecksum());
            existing.setServerChecksum(serverChecksum);
            existing.setPayload(req.getPayload());
            existing.setRawValue(req.getRawValue());
            existing.setEngValue(req.getEngValue());
            existing.setCycleNo(req.getCycleNo());
            existing.setFrameTime(req.getFrameTime());
            pieceMapper.updateById(existing);
            recountSession(session);
            vo.setPieceStatus(TvacConst.PieceStatus.VERIFIED);
            return maybeAutoAssemble(sessionMapper.selectById(session.getId()), vo);
        }
        if (!samePayload) {
            // 已验证好片却发来不同报文：协议冲突，不覆盖原片
            String reason = "重复片序号 " + req.getPieceSeq() + " 报文与首片不一致，拒绝覆盖（协议冲突）";
            vo.setPieceStatus(TvacConst.PieceStatus.BAD);
            vo.setBadReason(reason);
            vo.getBadPieceList().add(bad(channel.getChannelCode(), req.getPieceSeq(), reason));
            fillCounts(session, vo);
            return vo;
        }
        // 同报文但 CRC 仍不符：维持坏片判定
        vo.setPieceStatus(TvacConst.PieceStatus.BAD);
        vo.setBadReason(existing.getBadReason());
        vo.getBadPieceList().add(bad(channel.getChannelCode(), req.getPieceSeq(),
                existing.getBadReason()));
        fillCounts(session, vo);
        return vo;
    }

    private FrameAssemblyVo maybeAutoAssemble(TvacFrameSession session, FrameAssemblyVo vo) {
        if (session.getExpectedPieces() != null && session.getExpectedPieces() > 0) {
            long distinct = pieceMapper.selectCount(new QueryWrapper<TvacFramePiece>()
                    .eq("session_id", session.getId()));
            if (distinct >= session.getExpectedPieces()) {
                return doAssemble(session, vo);
            }
        }
        fillCounts(session, vo);
        return vo;
    }

    /** 显式触发重组：只把 VERIFIED 片逐通道投影成遥测帧，坏片继续隔离 */
    @Transactional(rollbackFor = Exception.class)
    public FrameAssemblyVo assemble(String sessionKey) {
        TvacFrameSession session = sessionMapper.selectOne(
                new QueryWrapper<TvacFrameSession>().eq("session_key", sessionKey));
        if (session == null) {
            throw BusinessException.of("重组会话不存在: " + sessionKey);
        }
        TvacPlan plan = planMapper.selectById(session.getPlanId());
        FrameAssemblyVo vo = newVo(session, plan == null ? null : plan.getPlanCode());
        return doAssemble(session, vo);
    }

    private FrameAssemblyVo doAssemble(TvacFrameSession session, FrameAssemblyVo vo) {
        TvacPlan plan = planMapper.selectById(session.getPlanId());
        String locked = lockGuard.lockedReason(plan.getId());

        List<TvacFramePiece> pieces = pieceMapper.selectList(new QueryWrapper<TvacFramePiece>()
                .eq("session_id", session.getId())
                .orderByAsc("channel_id").orderByAsc("piece_seq"));
        List<FrameAssemblyVo.AssembledFrame> frames = new ArrayList<>();
        List<FrameAssemblyVo.BadPiece> bads = new ArrayList<>();

        for (TvacFramePiece piece : pieces) {
            TvacChannel channel = channelMapper.selectById(piece.getChannelId());
            if (TvacConst.PieceStatus.BAD.equals(piece.getStatus())) {
                bads.add(bad(channel == null ? null : channel.getChannelCode(),
                        piece.getPieceSeq(), piece.getBadReason()));
                continue;
            }
            // VERIFIED 片在重组时刻仍可能因停用/锁定而失败：单通道隔离，不影响其他通道
            String nowBad = null;
            if (locked != null) {
                nowBad = locked;
            } else if (channel != null
                    && TvacConst.ChannelStatus.DISABLED.equals(channel.getStatus())) {
                nowBad = "通道 " + channel.getChannelCode() + " 已停用，坏帧隔离";
            } else if (channel != null && !channel.getArticleId().equals(session.getArticleId())) {
                nowBad = "通道归属不一致，坏帧隔离";
            }
            if (nowBad != null) {
                piece.setStatus(TvacConst.PieceStatus.BAD);
                piece.setBadReason(nowBad);
                pieceMapper.updateById(piece);
                bads.add(bad(channel == null ? null : channel.getChannelCode(),
                        piece.getPieceSeq(), nowBad));
                continue;
            }
            if (channel == null) {
                bads.add(bad(null, piece.getPieceSeq(), "通道不存在"));
                continue;
            }

            String frameSeq = session.getFrameNo() + "#" + channel.getChannelCode()
                    + "#" + piece.getPieceSeq();
            TvacObservation obs = new TvacObservation();
            obs.setRecordType(TvacConst.RecordType.FRAME);
            obs.setBizKey(frameSeq);
            obs.setFrameSeq(frameSeq);
            obs.setPlanId(plan.getId());
            obs.setArticleId(plan.getArticleId());
            obs.setChannelId(channel.getId());
            obs.setChannelCode(channel.getChannelCode());
            obs.setCycleNo(piece.getCycleNo() == null ? 1 : piece.getCycleNo());
            obs.setObserveTime(piece.getFrameTime() == null ? LocalDateTime.now() : piece.getFrameTime());
            obs.setRawValue(piece.getRawValue() == null ? piece.getPayload() : piece.getRawValue());
            obs.setEngValue(piece.getEngValue());
            obs.setSourceDevice(session.getSourceDevice());
            obs.setObjectCode(null);

            ObservationProjector.Outcome outcome;
            try {
                outcome = projector.project(obs, null);
            } catch (ObservationProjector.RejectException e) {
                // 投影层业务拒绝：隔离该通道片，继续重组其余通道
                piece.setStatus(TvacConst.PieceStatus.BAD);
                piece.setBadReason(e.getMessage());
                pieceMapper.updateById(piece);
                bads.add(bad(channel.getChannelCode(), piece.getPieceSeq(), e.getMessage()));
                continue;
            }
            FrameAssemblyVo.AssembledFrame af = new FrameAssemblyVo.AssembledFrame();
            af.setFrameSeq(frameSeq);
            af.setChannelCode(channel.getChannelCode());
            af.setPieceSeq(piece.getPieceSeq());
            af.setFrameId(outcome.targetId);
            af.setResult(outcome.result);
            frames.add(af);
        }

        recountSession(session);
        session = sessionMapper.selectById(session.getId());
        session.setStatus("ASSEMBLED");
        session.setAssembledTime(LocalDateTime.now());
        session.setRemark("重组完成：好片 " + session.getGoodPieces() + "，坏片 "
                + session.getBadPieces() + "，本次落帧 " + frames.size());
        sessionMapper.updateById(session);

        vo.setStatus("ASSEMBLED");
        vo.setFrames(frames);
        vo.setBadPieceList(bads);
        fillCounts(session, vo);
        return vo;
    }

    public TvacFrameSession getSession(String sessionKey) {
        TvacFrameSession session = sessionMapper.selectOne(
                new QueryWrapper<TvacFrameSession>().eq("session_key", sessionKey));
        if (session == null) {
            throw BusinessException.of("重组会话不存在: " + sessionKey);
        }
        return session;
    }

    public List<TvacFramePiece> listPieces(Long sessionId) {
        return pieceMapper.selectList(new QueryWrapper<TvacFramePiece>()
                .eq("session_id", sessionId)
                .orderByAsc("receive_seq").orderByAsc("id"));
    }

    // ---------------- helpers ----------------

    private FrameAssemblyVo newVo(TvacFrameSession session, String planCode) {
        FrameAssemblyVo vo = new FrameAssemblyVo();
        vo.setSessionKey(session.getSessionKey());
        vo.setFrameNo(session.getFrameNo());
        vo.setPlanCode(planCode);
        vo.setSourceDevice(session.getSourceDevice());
        vo.setExpectedPieces(session.getExpectedPieces());
        vo.setStatus(session.getStatus());
        vo.setFrames(new ArrayList<>());
        vo.setBadPieceList(new ArrayList<>());
        return vo;
    }

    private FrameAssemblyVo rejectPiece(FrameAssemblyVo vo, FramePieceRequest req, String reason) {
        vo.setPieceStatus(TvacConst.PieceStatus.BAD);
        vo.setPieceSeq(req.getPieceSeq());
        vo.setChannelCode(req.getChannelCode());
        vo.setBadReason(reason);
        vo.setBadPieceList(new ArrayList<>());
        vo.getBadPieceList().add(bad(req.getChannelCode(), req.getPieceSeq(), reason));
        TvacFrameSession session = sessionMapper.selectOne(
                new QueryWrapper<TvacFrameSession>().eq("session_key", req.getSessionKey()));
        fillCounts(session, vo);
        return vo;
    }

    private void recountSession(TvacFrameSession session) {
        List<TvacFramePiece> all = pieceMapper.selectList(
                new QueryWrapper<TvacFramePiece>().eq("session_id", session.getId()));
        int good = 0;
        int bad = 0;
        for (TvacFramePiece p : all) {
            if (TvacConst.PieceStatus.BAD.equals(p.getStatus())) {
                bad++;
            } else {
                good++;
            }
        }
        session.setGoodPieces(good);
        session.setBadPieces(bad);
        sessionMapper.updateById(session);
    }

    private void fillCounts(TvacFrameSession session, FrameAssemblyVo vo) {
        if (session == null) {
            return;
        }
        vo.setGoodPieces(nz(session.getGoodPieces()));
        vo.setBadPieces(nz(session.getBadPieces()));
        vo.setReceivedPieces(nz(session.getGoodPieces()) + nz(session.getBadPieces()));
        vo.setStatus(session.getStatus());
    }

    private static FrameAssemblyVo.BadPiece bad(String channelCode, Integer pieceSeq, String reason) {
        FrameAssemblyVo.BadPiece b = new FrameAssemblyVo.BadPiece();
        b.setChannelCode(channelCode);
        b.setPieceSeq(pieceSeq);
        b.setReason(reason);
        return b;
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }
}
