package com.evops.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.common.BusinessException;
import com.evops.constant.TvacConst;
import com.evops.dto.ChannelCreateRequest;
import com.evops.dto.ChannelUpdateRequest;
import com.evops.entity.TvacArticle;
import com.evops.entity.TvacChannel;
import com.evops.entity.TvacTmFrame;
import com.evops.mapper.TvacArticleMapper;
import com.evops.mapper.TvacChannelMapper;
import com.evops.mapper.TvacTmFrameMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
public class TvacChannelService {

    private final TvacChannelMapper channelMapper;
    private final TvacArticleMapper articleMapper;
    private final TvacTmFrameMapper frameMapper;

    public TvacChannelService(TvacChannelMapper channelMapper,
                              TvacArticleMapper articleMapper,
                              TvacTmFrameMapper frameMapper) {
        this.channelMapper = channelMapper;
        this.articleMapper = articleMapper;
        this.frameMapper = frameMapper;
    }

    public TvacChannel create(ChannelCreateRequest req) {
        TvacArticle article = articleMapper.selectById(req.getArticleId());
        if (article == null) {
            throw BusinessException.of("试验件不存在: " + req.getArticleId());
        }
        validateMeasureType(req.getMeasureType());
        validateLimits(req.getUpperLimit(), req.getLowerLimit());
        TvacChannel channel = new TvacChannel();
        channel.setChannelCode(req.getChannelCode());
        channel.setChannelName(req.getChannelName());
        channel.setArticleId(req.getArticleId());
        channel.setMeasureType(req.getMeasureType());
        channel.setUnit(req.getUnit());
        channel.setUpperLimit(req.getUpperLimit());
        channel.setLowerLimit(req.getLowerLimit());
        channel.setStatus(TvacConst.ChannelStatus.ENABLED);
        channel.setRemark(req.getRemark());
        channelMapper.insert(channel);
        return channel;
    }

    private void validateMeasureType(String measureType) {
        Set<String> valid = new HashSet<>(Arrays.asList(
                TvacConst.MeasureType.TEMPERATURE,
                TvacConst.MeasureType.PRESSURE,
                TvacConst.MeasureType.VOLTAGE,
                TvacConst.MeasureType.CURRENT,
                TvacConst.MeasureType.OTHER));
        if (!valid.contains(measureType)) {
            throw BusinessException.of("不支持的测量量类型: " + measureType);
        }
    }

    private void validateLimits(BigDecimal upper, BigDecimal lower) {
        if (upper != null && lower != null && upper.compareTo(lower) <= 0) {
            throw BusinessException.of("通道上限必须高于下限");
        }
    }

    public TvacChannel update(Long id, ChannelUpdateRequest req) {
        TvacChannel channel = getById(id);
        if (req.getChannelName() != null) {
            channel.setChannelName(req.getChannelName());
        }
        if (req.getUnit() != null) {
            channel.setUnit(req.getUnit());
        }
        if (req.getUpperLimit() != null) {
            channel.setUpperLimit(req.getUpperLimit());
        }
        if (req.getLowerLimit() != null) {
            channel.setLowerLimit(req.getLowerLimit());
        }
        validateLimits(channel.getUpperLimit(), channel.getLowerLimit());
        if (req.getRemark() != null) {
            channel.setRemark(req.getRemark());
        }
        channelMapper.updateById(channel);
        return channel;
    }

    public TvacChannel getById(Long id) {
        TvacChannel channel = channelMapper.selectById(id);
        if (channel == null) {
            throw BusinessException.of("遥测通道不存在: " + id);
        }
        return channel;
    }

    public List<TvacChannel> list(Long articleId, String status) {
        QueryWrapper<TvacChannel> qw = new QueryWrapper<>();
        if (articleId != null) {
            qw.eq("article_id", articleId);
        }
        if (status != null && !status.isEmpty()) {
            qw.eq("status", status);
        }
        qw.orderByDesc("id");
        return channelMapper.selectList(qw);
    }

    /** ENABLED <-> DISABLED，停用通道不再接收新帧 */
    public TvacChannel changeStatus(Long id, String target) {
        TvacChannel channel = getById(id);
        if (!TvacConst.ChannelStatus.ENABLED.equals(target)
                && !TvacConst.ChannelStatus.DISABLED.equals(target)) {
            throw BusinessException.of("通道状态只能为 ENABLED 或 DISABLED");
        }
        if (target.equals(channel.getStatus())) {
            throw BusinessException.of("通道已处于目标状态: " + target);
        }
        channel.setStatus(target);
        channelMapper.updateById(channel);
        return channel;
    }

    /** 删除保护：已录入过遥测帧的通道不能直接删除 */
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        getById(id);
        Long frameCount = frameMapper.selectCount(
                new QueryWrapper<TvacTmFrame>().eq("channel_id", id));
        if (frameCount != null && frameCount > 0) {
            throw BusinessException.of("通道已录入 " + frameCount
                    + " 帧遥测数据，不能直接删除，请改用停用");
        }
        channelMapper.deleteById(id);
    }
}
