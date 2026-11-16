package in.boss.admin.security;

import java.util.UUID;

public record AdminPrincipal(UUID adminId, String firebaseUid, String email) { }
