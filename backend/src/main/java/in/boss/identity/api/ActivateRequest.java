package in.boss.identity.api;

public record ActivateRequest(String token, String password, String displayName) { }
