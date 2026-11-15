package in.boss.admin.security;

public interface AdminTokenVerifier {
    VerifiedAdminToken verify(String token);

    record VerifiedAdminToken(String uid, String email) { }
}
