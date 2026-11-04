import { request } from "./client";

export interface AuthenticatedUser {
  userId: string;
  username: string;
  realName: string;
  roles: string[];
}

export interface LogoutResponse {
  status: "logged_out";
}

export function login(username: string, password: string): Promise<AuthenticatedUser> {
  return request<AuthenticatedUser>("/api/v1/auth/login", {
    method: "POST",
    body: { username, password },
  });
}

export function getCurrentUser(): Promise<AuthenticatedUser> {
  return request<AuthenticatedUser>("/api/v1/auth/me");
}

export function logout(): Promise<LogoutResponse> {
  return request<LogoutResponse>("/api/v1/auth/logout", { method: "POST" });
}
