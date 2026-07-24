package com.family.scrollshield.service;

public class LeaseException extends RuntimeException {
    private final String code;

    public LeaseException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    public static LeaseException memberNotFound() {
        return new LeaseException("MEMBER_NOT_FOUND", "成员不存在");
    }

    public static LeaseException activeLeaseExists() {
        return new LeaseException("ACTIVE_LEASE_EXISTS", "该成员已有活动租约");
    }

    public static LeaseException dailyQuotaExhausted() {
        return new LeaseException("DAILY_QUOTA_EXHAUSTED", "当日观看额度已用尽");
    }

    public static LeaseException bedtimeWindow() {
        return new LeaseException("BEDTIME_WINDOW", "处于睡前禁刷窗口");
    }

    public static LeaseException crossedMidnight() {
        return new LeaseException("CROSSED_MIDNIGHT", "会话已跨午夜，请重新申请租约");
    }

    public static LeaseException leaseNotFound() {
        return new LeaseException("LEASE_NOT_FOUND", "租约不存在");
    }

    public static LeaseException leaseNotActive() {
        return new LeaseException("LEASE_NOT_ACTIVE", "租约已不再活动");
    }

    public static LeaseException leaseExpired() {
        return new LeaseException("LEASE_EXPIRED", "租约已过期");
    }

    public static LeaseException extensionAlreadyUsed() {
        return new LeaseException("EXTENSION_ALREADY_USED", "当日已使用过一次延长");
    }

    public static LeaseException extensionBlockedByBedtime() {
        return new LeaseException("EXTENSION_BLOCKED_BY_BEDTIME", "延长会突破睡前窗口");
    }

    public static LeaseException invalidTimeZone(String zone) {
        return new LeaseException("INVALID_TIME_ZONE", "无效时区: " + zone);
    }

    public static LeaseException planClosed() {
        return new LeaseException("PLAN_CLOSED", "当日观看计划已关闭");
    }

    public static LeaseException sessionExceedsSingleLimit() {
        return new LeaseException("SESSION_EXCEEDS_SINGLE_LIMIT", "本次会话将超过单次时长上限");
    }
}
