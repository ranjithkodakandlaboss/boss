package in.boss.admin.security;

import com.google.firebase.auth.FirebaseToken;

@FunctionalInterface
public interface FirebaseIdTokenVerification {
    FirebaseToken verify(String token) throws Exception;
}
