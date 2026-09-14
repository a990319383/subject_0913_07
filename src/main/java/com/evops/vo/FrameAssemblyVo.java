package com.evops.vo;

import lombok.Data;

import java.util.List;

/**
 * 帧重组结果：会话状态、收片统计、本次落库/隔离明细。
 */
@Data
public class FrameAssemblyVo {
    private String sessionKey;
    private String frameNo;
    private String planCode;
    private String sourceDevice;
    private Integer expectedPieces;
    private int receivedPieces;
    private int goodPieces;
    private int badPieces;
    /** ASSEMBLING / ASSEMBLED */
    private String status;
    /** 本次到片判定：VERIFIED / BAD / DUPLICATED */
    private String pieceStatus;
    private Integer pieceSeq;
    private String channelCode;
    private String badReason;
    /** 本次重组落库的遥测帧（每通道每片一帧） */
    private List<AssembledFrame> frames;
    /** 被隔离的坏片（单通道坏帧不影响其他通道） */
    private List<BadPiece> badPieceList;

    @Data
    public static class AssembledFrame {
        private String frameSeq;
        private String channelCode;
        private Integer pieceSeq;
        private Long frameId;
        private String result;
        private String limitFlag;
    }

    @Data
    public static class BadPiece {
        private String channelCode;
        private Integer pieceSeq;
        private String reason;
    }
}
