import { getApp, getApps, initializeApp } from "@firebase/app";
import {
  getAuth,
  GoogleAuthProvider,
  onAuthStateChanged,
  signInWithEmailAndPassword,
  signInWithPopup,
  signOut,
  type Auth,
  type User,
} from "@firebase/auth";

const firebaseConfig = {
  apiKey: import.meta.env.VITE_PLATFORM_ADMIN_FIREBASE_API_KEY,
  authDomain: import.meta.env.VITE_PLATFORM_ADMIN_FIREBASE_AUTH_DOMAIN,
  projectId: import.meta.env.VITE_PLATFORM_ADMIN_FIREBASE_PROJECT_ID,
  appId: import.meta.env.VITE_PLATFORM_ADMIN_FIREBASE_APP_ID,
};

const missingFirebaseConfig = Object.entries(firebaseConfig)
  .filter(([, value]) => !value)
  .map(([key]) => key);

let adminAuth: Auth | undefined;

export function getAdminAuth(): Auth | undefined {
  if (missingFirebaseConfig.length > 0) return undefined;
  if (!adminAuth) {
    const app = getApps().some(({ name }) => name === "boss-platform-admin")
      ? getApp("boss-platform-admin")
      : initializeApp(firebaseConfig, "boss-platform-admin");
    adminAuth = getAuth(app);
  }
  return adminAuth;
}

export function getMissingFirebaseConfig(): string[] {
  return missingFirebaseConfig;
}

export function observeAdminUser(
  auth: Auth,
  onChange: (user: User | null) => void,
  onError: (error: Error) => void,
): () => void {
  return onAuthStateChanged(auth, onChange, onError);
}

export async function signInWithEmail(auth: Auth, email: string, password: string): Promise<void> {
  await signInWithEmailAndPassword(auth, email, password);
}

export async function signInWithGoogle(auth: Auth): Promise<void> {
  await signInWithPopup(auth, new GoogleAuthProvider());
}

export async function signOutAdmin(auth: Auth): Promise<void> {
  await signOut(auth);
}

export function signInErrorMessage(code: string | undefined): string {
  switch (code) {
    case "auth/invalid-credential":
    case "auth/wrong-password":
    case "auth/user-not-found":
      return "The email or password was not accepted.";
    case "auth/too-many-requests":
      return "Sign-in is temporarily unavailable. Try again later.";
    case "auth/popup-closed-by-user":
      return "The Google sign-in window was closed before sign-in completed.";
    default:
      return "Sign-in failed. Check your credentials or contact your administrator.";
  }
}
