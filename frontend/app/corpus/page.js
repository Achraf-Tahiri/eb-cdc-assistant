"use client";

import { useEffect, useRef, useState } from "react";
import { useRouter } from "next/navigation";
import { api, API, getSession } from "../../lib/api";

export default function CorpusPage() {
  const router = useRouter();
  const fileRef = useRef(null);
  const [totalChunks, setTotalChunks] = useState(null);
  const [label, setLabel] = useState("");
  const [content, setContent] = useState("");
  const [query, setQuery] = useState("");
  const [results, setResults] = useState(null);
  const [busy, setBusy] = useState("");
  const [notice, setNotice] = useState("");
  const [error, setError] = useState("");

  useEffect(() => {
    const s = getSession();
    if (!s) {
      router.push("/");
      return;
    }
    if (s.user.role === "PRESTATAIRE") {
      router.push("/documents");
      return;
    }
    refresh();
  }, []);

  async function refresh() {
    try {
      const c = await api("/api/rag/corpus");
      setTotalChunks(c.totalChunks);
    } catch (e) {
      setError(e.message);
    }
  }

  async function ingestText() {
    setError(""); setNotice(""); setBusy("text");
    try {
      const r = await api("/api/rag/ingest-text", {
        method: "POST",
        body: JSON.stringify({ label: label || "Document collé", content }),
      });
      setNotice(`${r.chunksInserted} passages ajoutés au corpus.`);
      setLabel(""); setContent("");
      setTotalChunks(r.totalChunks);
    } catch (e) {
      setError(e.message);
    } finally {
      setBusy("");
    }
  }

  async function ingestFile() {
    const file = fileRef.current?.files?.[0];
    if (!file) return;
    setError(""); setNotice(""); setBusy("file");
    try {
      const fd = new FormData();
      fd.append("file", file);
      const r = await api("/api/rag/ingest", { method: "POST", body: fd });
      setNotice(`« ${file.name} » : ${r.chunksInserted} passages ajoutés.`);
      fileRef.current.value = "";
      setTotalChunks(r.totalChunks);
    } catch (e) {
      setError(e.message);
    } finally {
      setBusy("");
    }
  }

  async function search() {
    setError(""); setResults(null); setBusy("search");
    try {
      setResults(await api("/api/rag/search", {
        method: "POST",
        body: JSON.stringify({ query }),
      }));
    } catch (e) {
      setError(e.message);
    } finally {
      setBusy("");
    }
  }

  return (
    <div>
      <h1 className="page-title">Corpus documentaire</h1>
      <p className="page-sub">
        Les anciens EB/CDC validés alimentent les suggestions de rédaction.
        {totalChunks != null && (
          <> Actuellement : <strong>{totalChunks}</strong> passages indexés.</>
        )}
      </p>
      {error && <div className="error-box">{error}</div>}
      {notice && <div className="panel" style={{ padding: "10px 14px" }}>{notice}</div>}

      <div className="grid-2">
        <div>
          <div className="panel">
            <h2>Ingérer un fichier</h2>
            <p className="panel-sub">
              docx, pdf, txt ou md — le texte est extrait, découpé par sections,
              puis indexé (nomic-embed-text → pgvector).
            </p>
            <div className="field">
              <input type="file" ref={fileRef} accept=".docx,.pdf,.txt,.md" />
            </div>
            <button className="btn btn-accent" onClick={ingestFile} disabled={busy !== ""}>
              {busy === "file" ? "Indexation…" : "Indexer le fichier"}
            </button>
          </div>

          <div className="panel">
            <h2>Coller du texte</h2>
            <div className="field">
              <label>Libellé de la source</label>
              <input
                value={label}
                onChange={(e) => setLabel(e.target.value)}
                placeholder="CDC Portail client 2024"
              />
            </div>
            <div className="field">
              <label>Contenu (Markdown de préférence)</label>
              <textarea
                rows={10}
                value={content}
                onChange={(e) => setContent(e.target.value)}
              />
            </div>
            <button
              className="btn btn-accent"
              onClick={ingestText}
              disabled={busy !== "" || !content.trim()}
            >
              {busy === "text" ? "Indexation…" : "Indexer le texte"}
            </button>
          </div>
        </div>

        <div className="panel">
          <h2>Tester la recherche sémantique</h2>
          <p className="panel-sub">
            La même recherche alimente les suggestions dans l'éditeur.
          </p>
          <div className="field">
            <input
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              onKeyDown={(e) => e.key === "Enter" && query.trim() && search()}
              placeholder="contraintes de reprise de données"
            />
          </div>
          <button
            className="btn btn-secondary"
            onClick={search}
            disabled={busy !== "" || !query.trim()}
          >
            {busy === "search" ? "Recherche…" : "Rechercher"}
          </button>
          {results && results.length === 0 && (
            <p className="empty">Aucun passage trouvé — le corpus est-il vide ?</p>
          )}
          {results && results.length > 0 && (
            <div style={{ marginTop: 14 }}>
              {results.map((r, i) => (
                <div key={i} className="suggestion">
                  <div className="src">
                    {r.source_label}
                    {r.section_title ? ` · ${r.section_title}` : ""} · score {r.score}
                  </div>
                  <p style={{ margin: "6px 0 0" }}>{r.chunk_text}</p>
                </div>
              ))}
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
