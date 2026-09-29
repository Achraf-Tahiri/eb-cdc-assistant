export const API = process.env.NEXT_PUBLIC_API_URL || "http://localhost:8080";

export function getSession() {
  if (typeof window === "undefined") return null;
  try {
    return JSON.parse(localStorage.getItem("ebcdc_session"));
  } catch {
    return null;
  }
}

export function setSession(session) {
  localStorage.setItem("ebcdc_session", JSON.stringify(session));
}

export function clearSession() {
  localStorage.removeItem("ebcdc_session");
}

export async function api(path, options = {}) {
  const session = getSession();
  const headers = { ...(options.headers || {}) };
  if (!(options.body instanceof FormData)) {
    headers["Content-Type"] = "application/json";
  }
  if (session?.token && path !== "/api/auth/login") {
    headers["Authorization"] = `Bearer ${session.token}`;
  }
  const res = await fetch(`${API}${path}`, { ...options, headers });
  if (res.status === 401 && path !== "/api/auth/login") {
    clearSession();
    if (typeof window !== "undefined") window.location.href = "/";
    throw new Error("Session expirée, reconnectez-vous");
  }
  const text = await res.text();
  let data = null;
  try {
    data = text ? JSON.parse(text) : null;
  } catch {
    data = text;
  }
  if (!res.ok) {
    throw new Error((data && data.error) || `Erreur ${res.status}`);
  }
  return data;
}
