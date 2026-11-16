package in.boss.admin.domain;

public record AdminHttpResult(int status, Object body, boolean replayed) { }
