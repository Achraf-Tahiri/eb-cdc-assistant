"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { getSession, clearSession } from "../lib/api";

const ROLE_LABELS = {
  METIER: "Équipe métier",
  PROJET: "Équipe projet",
  PRESTATAIRE: "Prestataire",
};

export default function Nav() {
  const [session, setSessionState] = useState(null);
  const pathname = usePathname();
  const router = useRouter();

  useEffect(() => {
    setSessionState(getSession());
  }, [pathname]);

  if (!session?.user) return null;

  const { user } = session;
  const internal = user.role !== "PRESTATAIRE";
  return (
    <header className="nav">
      <div className="nav-inner">
        <Link href="/documents" className="nav-brand">
          Registre EB<span className="nav-sep">/</span>CDC
        </Link>
        <nav className="nav-links">
          <Link href="/documents" className={pathname.startsWith("/documents") ? "active" : ""}>
            Documents
          </Link>
          {internal && (
            <Link href="/corpus" className={pathname.startsWith("/corpus") ? "active" : ""}>
              Corpus
            </Link>
          )}
          {internal && (
            <Link href="/analytics" className={pathname.startsWith("/analytics") ? "active" : ""}>
              Analytique
            </Link>
          )}
        </nav>
        <div className="nav-user">
          <span className="nav-role">{ROLE_LABELS[user.role] || user.role}</span>
          <span className="nav-name">
            {user.displayName}
            {user.providerName ? ` · ${user.providerName}` : ""}
          </span>
          <button
            className="btn btn-ghost"
            onClick={() => {
              clearSession();
              router.push("/");
            }}
          >
            Déconnexion
          </button>
        </div>
      </div>
    </header>
  );
}
