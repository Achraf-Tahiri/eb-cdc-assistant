"use client";

import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import { api, getSession, setSession } from "../lib/api";

export default function LoginPage() {
  const router = useRouter();
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    if (getSession()) router.push("/documents");
  }, []);

  async function submit() {
    setError("");
    setLoading(true);
    try {
      const data = await api("/api/auth/login", {
        method: "POST",
        body: JSON.stringify({ email, password }),
      });
      setSession(data);
      router.push("/documents");
    } catch (e) {
      setError(e.message);
    } finally {
      setLoading(false);
    }
  }

  return (
    <div className="login-wrap">
      <div className="login-card">
        <div className="eyebrow">Assistant RAG métier</div>
        <h1>Registre EB/CDC</h1>
        <p className="panel-sub">
          Suivi des expressions de besoin, audit des délais prestataires.
        </p>
        {error && <div className="error-box">{error}</div>}
        <div className="field">
          <label htmlFor="email">Email</label>
          <input
            id="email"
            type="email"
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            onKeyDown={(e) => e.key === "Enter" && submit()}
            placeholder="metier@demo.fr"
          />
        </div>
        <div className="field">
          <label htmlFor="password">Mot de passe</label>
          <input
            id="password"
            type="password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            onKeyDown={(e) => e.key === "Enter" && submit()}
          />
        </div>
        <button className="btn btn-accent" onClick={submit} disabled={loading}>
          {loading ? "Connexion..." : "Se connecter"}
        </button>
        <div className="login-hint">
          Comptes de démonstration (mot de passe <code>demo1234</code>) :<br />
          <code>metier@demo.fr</code> · <code>projet@demo.fr</code> ·{" "}
          <code>prestataire@demo.fr</code>
        </div>
      </div>
    </div>
  );
}
