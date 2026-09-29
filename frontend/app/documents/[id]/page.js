"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { useParams, useRouter } from "next/navigation";
import { api, getSession } from "../../../lib/api";
import StateBadge from "../../../components/StateBadge";

const ACTION_LABELS = {
  SUBMIT: "Soumettre au projet",
  VALIDATE: "Valider",
  REQUEST_CORRECTION: "Demander une correction",
  SEND: "Transmettre au prestataire",
  REQUEST_CLARIFICATION: "Demander une clarification",
  RESOLVE: "Renvoyer au prestataire",
  ACCEPT: "Accepter l'implémentation",
  CLOSE: "Clôturer",
};

function fmtDate(iso) {
  if (!iso) return "";
  return new Date(iso).toLocaleString("fr-FR", {
    dateStyle: "short",
    timeStyle: "short",
  });
}

export default function DocumentPage() {
  const { id } = useParams();
  const router = useRouter();
  const [session, setSessionState] = useState(null);
  const [doc, setDoc] = useState(null);
  const [content, setContent] = useState("");
  const [actions, setActions] = useState([]);
  const [timeline, setTimeline] = useState([]);
  const [clarifs, setClarifs] = useState([]);
  const [comment, setComment] = useState("");
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");

  // Clarifications (prestataire)
  const [question, setQuestion] = useState("");
  const [sectionRef, setSectionRef] = useState("");
  const [answers, setAnswers] = useState({});

  // RAG
  const [activeJob, setActiveJob] = useState(null);
  const [suggestTitle, setSuggestTitle] = useState("Besoins fonctionnels");
  const pollRef = useRef(null);

  const load = useCallback(async () => {
    try {
      const d = await api(`/api/documents/${id}`);
      setDoc(d);
      setContent(d.content || "");
      setActions(await api(`/api/documents/${id}/available-actions`));
      setTimeline(await api(`/api/documents/${id}/timeline`));
      setClarifs(await api(`/api/documents/${id}/clarifications`));
      if (getSession()?.user?.role !== "PRESTATAIRE") {
        const jobs = await api(`/api/rag/jobs?documentId=${id}`);
        setActiveJob(jobs[0] || null);
      }
    } catch (e) {
      setError(e.message);
    }
  }, [id]);

  useEffect(() => {
    const s = getSession();
    if (!s) {
      router.push("/");
      return;
    }
    setSessionState(s);
    load();
  }, [load]);

  // Polling du job RAG actif
  useEffect(() => {
    if (!activeJob || ["DONE", "FAILED"].includes(activeJob.status)) {
      if (pollRef.current) clearInterval(pollRef.current);
      return;
    }
    pollRef.current = setInterval(async () => {
      try {
        const j = await api(`/api/rag/jobs/${activeJob.id}`);
        setActiveJob(j);
      } catch {
        clearInterval(pollRef.current);
      }
    }, 4000);
    return () => clearInterval(pollRef.current);
  }, [activeJob?.id, activeJob?.status]);

  if (!doc) {
    return error ? <div className="error-box">{error}</div> : <p className="empty">Chargement…</p>;
  }

  const role = session?.user?.role;
  const workflowButtons = actions.filter((a) => !["REQUEST_CLARIFICATION", "RESOLVE"].includes(a));
  const canEdit =
    role !== "PRESTATAIRE" &&
    (role === "PROJET" ||
      ["BROUILLON", "CORRECTION_METIER", "CLARIFICATION_DEMANDEE"].includes(doc.state));

  async function saveVersion() {
    setError(""); setNotice("");
    try {
      const v = await api(`/api/documents/${id}/versions`, {
        method: "PUT",
        body: JSON.stringify({ content }),
      });
      setNotice(`Version ${v.versionNum} enregistrée.`);
      load();
    } catch (e) {
      setError(e.message);
    }
  }

  async function deleteDraft() {
    if (!window.confirm(
      "Supprimer définitivement ce brouillon ?\n" +
      "Cette action est irréversible (versions et analyses comprises)."
    )) return;
    setError(""); setNotice("");
    try {
      await api(`/api/documents/${id}`, { method: "DELETE" });
      router.push("/documents");
    } catch (e) {
      setError(e.message);
    }
  }

  // Insère le texte d'une suggestion RAG à la fin de la section ciblée.
  // Si la section n'existe pas, elle est créée en fin de document.
  function insertSuggestion(s) {
    const title = activeJob?.sectionTitle || suggestTitle || "Section enrichie";
    setContent((prev) => insertIntoSection(prev, title, s.texte));
    setNotice(
      `Suggestion « ${s.titre} » insérée dans la section « ${title} » — ` +
      "relisez, ajustez, puis cliquez sur « Enregistrer une version »."
    );
  }

  async function doAction(action) {
    setError(""); setNotice("");
    if (canEdit && content !== doc.content) {
      setError("Enregistrez vos modifications avant de changer l’état du document.");
      return;
    }
    try {
      await api(`/api/documents/${id}/actions`, {
        method: "POST",
        body: JSON.stringify({ action, comment }),
      });
      setComment("");
      setNotice(`Action « ${ACTION_LABELS[action] || action} » appliquée.`);
      load();
    } catch (e) {
      setError(e.message);
    }
  }

  async function askClarification() {
    setError("");
    try {
      const r = await api(`/api/documents/${id}/clarifications`, {
        method: "POST",
        body: JSON.stringify({ question, sectionRef }),
      });
      setQuestion(""); setSectionRef("");
      setNotice(
        r.daysSinceProviderReceived >= 0
          ? `Question envoyée (posée ${r.daysSinceProviderReceived} j après réception).`
          : "Question envoyée."
      );
      load();
    } catch (e) {
      setError(e.message);
    }
  }

  async function resolveClarification(cid) {
    setError("");
    try {
      await api(`/api/clarifications/${cid}/resolve`, {
        method: "POST",
        body: JSON.stringify({ resolution: answers[cid] || "" }),
      });
      setAnswers({ ...answers, [cid]: "" });
      load();
    } catch (e) {
      setError(e.message);
    }
  }

  async function launchJob(jobType) {
    setError("");
    try {
      // Sauvegarde silencieuse : toute analyse porte sur ce que l'utilisateur voit.
      if (canEdit) {
        await api(`/api/documents/${id}/versions`, {
          method: "PUT",
          body: JSON.stringify({ content }),
        });
      }
      const j = await api("/api/rag/jobs", {
        method: "POST",
        body: JSON.stringify({
          documentId: id,
          jobType,
          sectionTitle: suggestTitle,
          draft: extractSection(content, suggestTitle),
        }),
      });
      setActiveJob({ ...j, jobType, status: "PENDING" });
    } catch (e) {
      setError(e.message);
    }
  }

  return (
    <div>
      <div className="eyebrow">
        {doc.docType === "EB" ? "Expression de besoin" : "Cahier des charges"}
        {doc.projectCode ? ` · ${doc.projectCode}` : ""}
        {doc.provider ? ` · ${doc.provider}` : ""}
      </div>
      <h1 className="page-title">{doc.title}</h1>
      <p className="page-sub">
        <StateBadge state={doc.state} />{" "}
        <span style={{ marginLeft: 8 }}>
          Version {doc.versionNum} — {doc.versionAuthor}, {fmtDate(doc.versionAt)}
        </span>
      </p>

      {error && <div className="error-box">{error}</div>}
      {notice && <div className="panel" style={{ padding: "10px 14px" }}>{notice}</div>}

      {doc.state === "BROUILLON" && role === "METIER" && (
        <div className="panel" style={{ padding: "10px 14px" }}>
          <div className="btn-row" style={{ alignItems: "center" }}>
            <span className="panel-sub" style={{ margin: 0, flex: 1 }}>
              Ce document est un brouillon jamais soumis : son auteur peut encore
              le supprimer. Dès la première soumission, il devient permanent
              (traçabilité du journal d'audit).
            </span>
            <button className="btn btn-danger" onClick={deleteDraft}>
              Supprimer le brouillon
            </button>
          </div>
        </div>
      )}

      {workflowButtons.length > 0 && (
        <div className="panel">
          <h2>Actions de workflow</h2>
          <p className="panel-sub">
            Chaque action est horodatée dans le journal d'audit.
          </p>
          <div className="field">
            <label>Commentaire (joint à l'action)</label>
            <input
              value={comment}
              onChange={(e) => setComment(e.target.value)}
              placeholder="Facultatif"
            />
          </div>
          <div className="btn-row">
            {workflowButtons.map((a) => (
                <button key={a} className="btn" onClick={() => doAction(a)}>
                  {ACTION_LABELS[a] || a}
                </button>
              ))}
          </div>
        </div>
      )}

      <div className="grid-2">
        <div>
          <div className="panel">
            <h2>Contenu</h2>
            <p className="panel-sub">
              Markdown, sections avec des titres <code>##</code> — elles pilotent la
              vérification de complétude et le RAG.
            </p>
            <textarea
              className="editor"
              value={content}
              onChange={(e) => setContent(e.target.value)}
              readOnly={!canEdit}
            />
            {canEdit && (
              <div className="btn-row" style={{ marginTop: 12 }}>
                <button className="btn btn-accent" onClick={saveVersion}>
                  Enregistrer une version
                </button>
              </div>
            )}
          </div>

          <div className="panel">
            <h2>Clarifications prestataire</h2>
            <p className="panel-sub">
              Les jours écoulés depuis la réception sont calculés automatiquement —
              c'est la matière première de la détection des demandes tardives.
            </p>
            {clarifs.length === 0 && (
              <p className="empty">Aucune demande de clarification.</p>
            )}
            {clarifs.map((c) => (
              <div key={c.id} className={`clarif ${c.resolvedAt ? "resolved" : ""}`}>
                <div className="meta">
                  {fmtDate(c.requestedAt)}
                  {c.sectionRef ? ` · section « ${c.sectionRef} »` : ""}
                  {c.daysSinceProviderReceived != null && (
                    <>
                      {" · "}
                      <span className={c.late ? "late" : ""}>
                        posée {c.daysSinceProviderReceived} j après réception
                      </span>
                    </>
                  )}
                </div>
                <div>{c.question}</div>
                {c.resolvedAt ? (
                  <div className="answer">
                    <strong>Réponse ({fmtDate(c.resolvedAt)}) :</strong> {c.resolution}
                  </div>
                ) : (
                  role !== "PRESTATAIRE" && (
                    <div className="answer">
                      <div className="field">
                        <label>Réponse</label>
                        <textarea
                          rows={2}
                          value={answers[c.id] || ""}
                          onChange={(e) =>
                            setAnswers({ ...answers, [c.id]: e.target.value })
                          }
                        />
                      </div>
                      <button
                        className="btn btn-secondary"
                        onClick={() => resolveClarification(c.id)}
                        disabled={!(answers[c.id] || "").trim()}
                      >
                        Enregistrer la réponse
                      </button>
                    </div>
                  )
                )}
              </div>
            ))}
            {role === "PRESTATAIRE" &&
              ["TRANSMIS_PRESTATAIRE", "CLARIFICATION_DEMANDEE"].includes(doc.state) && (
                <div style={{ marginTop: 12 }}>
                  <div className="form-row">
                    <div className="field">
                      <label>Question</label>
                      <textarea
                        rows={2}
                        value={question}
                        onChange={(e) => setQuestion(e.target.value)}
                      />
                    </div>
                  </div>
                  <div className="form-row">
                    <div className="field">
                      <label>Section concernée</label>
                      <input
                        value={sectionRef}
                        onChange={(e) => setSectionRef(e.target.value)}
                        placeholder="Besoins fonctionnels"
                      />
                    </div>
                  </div>
                  <button
                    className="btn"
                    onClick={askClarification}
                    disabled={!question.trim()}
                  >
                    Envoyer la demande de clarification
                  </button>
                </div>
              )}
          </div>
        </div>

        <div>
          {role !== "PRESTATAIRE" && (
            <div className="panel">
              <h2>Assistant RAG</h2>
              <p className="panel-sub">
                Analyses asynchrones exécutées localement (qwen3:4b) — quelques
                minutes sur CPU.
              </p>
              <div className="btn-row" style={{ marginBottom: 12 }}>
                <button
                  className="btn btn-secondary"
                  onClick={() => launchJob("COMPLETENESS_CHECK")}
                >
                  Vérifier la complétude
                </button>
              </div>
              <div className="field">
                <label>Section à enrichir</label>
                <input
                  value={suggestTitle}
                  onChange={(e) => setSuggestTitle(e.target.value)}
                />
              </div>
              <div className="btn-row">
                <button
                  className="btn btn-secondary"
                  onClick={() => launchJob("SUGGESTION")}
                >
                  Suggérer des améliorations
                </button>
              </div>
              {activeJob && (
                <div style={{ marginTop: 16 }}>
                  <div className="btn-row" style={{ marginBottom: 8 }}>
                    <span className={`job-status ${activeJob.status}`}>
                      {activeJob.jobType === "SUGGESTION" ? "Suggestion" : "Complétude"} ·{" "}
                      {activeJob.status}
                    </span>
                  </div>
                  {activeJob.status === "FAILED" && (
                    <div className="error-box">{activeJob.error}</div>
                  )}
                  {activeJob.status === "DONE" && (
                    <JobResult
                      job={activeJob}
                      canEdit={canEdit}
                      onInsert={insertSuggestion}
                    />
                  )}
                </div>
              )}
            </div>
          )}

          <div className="panel">
            <h2>Journal d'audit</h2>
            <p className="panel-sub">Écriture seule, horodaté, non modifiable.</p>
            {timeline.length === 0 ? (
              <p className="empty">Aucune transition.</p>
            ) : (
              <ul className="timeline">
                {timeline.map((t, i) => (
                  <li key={i}>
                    <div className="when">{fmtDate(t.occurredAt)}</div>
                    <div className="what">
                      {ACTION_LABELS[t.action] || t.action} →{" "}
                      <StateBadge state={t.toState} />
                    </div>
                    <div className="who">{t.actorRole}</div>
                    {t.comment && <div className="comment">{t.comment}</div>}
                  </li>
                ))}
              </ul>
            )}
          </div>
        </div>
      </div>
    </div>
  );
}

// Insère `text` à la fin de la section `title` (avant le prochain titre).
// Le rapprochement des titres est tolérant aux accents et à la casse,
// comme extractSection. Si la section est introuvable, elle est créée.
function insertIntoSection(content, title, text) {
  const norm = (s) => s.toLowerCase().normalize("NFD").replace(/\p{M}/gu, "");
  const lines = (content || "").split("\n");
  let start = -1;
  let end = lines.length;
  for (let i = 0; i < lines.length; i++) {
    const m = lines[i].match(/^#{1,6}\s+(.*)/);
    if (!m) continue;
    if (start === -1) {
      if (norm(m[1]).includes(norm(title))) start = i;
    } else {
      end = i;
      break;
    }
  }
  const block = text.trim();
  if (start === -1) {
    // Section absente : on la crée en fin de document.
    const base = (content || "").replace(/\s+$/, "");
    return `${base}\n\n## ${title}\n\n${block}\n`;
  }
  // Retire les lignes vides de fin de section pour un rendu propre.
  let insertAt = end;
  while (insertAt > start + 1 && lines[insertAt - 1].trim() === "") insertAt--;
  const before = lines.slice(0, insertAt).join("\n");
  const after = lines.slice(insertAt).join("\n");
  return `${before}\n\n${block}\n${after ? "\n" + after : ""}`;
}

function extractSection(content, title) {
  if (!content || !title) return "";
  const lines = content.split("\n");
  const norm = (s) => s.toLowerCase().normalize("NFD").replace(/\p{M}/gu, "");
  let inSection = false;
  const out = [];
  for (const line of lines) {
    const m = line.match(/^#{1,6}\s+(.*)/);
    if (m) {
      if (inSection) break;
      inSection = norm(m[1]).includes(norm(title));
      continue;
    }
    if (inSection) out.push(line);
  }
  return out.join("\n").trim();
}

function JobResult({ job, canEdit, onInsert }) {
  const r = job.result;
  if (!r) return null;

  if (job.jobType === "SUGGESTION" && r.resultat?.suggestions) {
    return (
      <div>
        {job.sectionTitle && (
          <div className="eyebrow">Section ciblée : {job.sectionTitle}</div>
        )}
        {r.resultat.suggestions.map((s, i) => (
          <div key={i} className="suggestion">
            <strong>{s.titre}</strong>
            <p style={{ margin: "6px 0" }}>{s.texte}</p>
            <div className="src">
              Sources vérifiées : {(s.sources || []).map((source) =>
                `${source.label}${source.section ? ` · ${source.section}` : ""} (${Math.round(source.score * 100)} %)`
              ).join(" ; ")}
            </div>
            {canEdit && onInsert && (
              <div className="btn-row" style={{ marginTop: 8 }}>
                <button
                  className="btn btn-secondary"
                  onClick={() => onInsert(s)}
                >
                  Insérer dans la section
                </button>
              </div>
            )}
          </div>
        ))}
        {r.resultat.suggestions.length === 0 && (
          <p className="empty">{r.resultat.message || "Aucune amélioration fiable trouvée."}</p>
        )}
        <p className="panel-sub" style={{ marginTop: 10 }}>
          Le document entier a été comparé au corpus. Le pourcentage indique la
          proximité sémantique de la source ; relisez toujours le texte avant de l’insérer.
        </p>
      </div>
    );
  }

  if (job.jobType === "COMPLETENESS_CHECK" && r.checklist) {
    const c = r.checklist;
    return (
      <div>
        <div className="eyebrow">Checklist déterministe</div>
        <p className="panel-sub" style={{ marginTop: 6 }}>
          Contrôle automatique de structure. Une section courte est un signal à
          relire, pas forcément une erreur.
        </p>
        {(c.sections_obligatoires_manquantes || []).length > 0 ? (
          <ul className="result-list checklist-bad">
            {c.sections_obligatoires_manquantes.map((s, i) => (
              <li key={i}>Section obligatoire manquante : {s}</li>
            ))}
          </ul>
        ) : (
          <p className="checklist-ok">Toutes les sections obligatoires sont présentes.</p>
        )}
        {(c.sections_trop_courtes || []).length > 0 && (
          <ul className="result-list">
            {c.sections_trop_courtes.map((s, i) => (
              <li key={i}>Section courte à vérifier : {s}</li>
            ))}
          </ul>
        )}
        <div className="eyebrow" style={{ marginTop: 12 }}>
          {r.analyse_globale ? "Analyse IA globale" : "Ancienne analyse IA par section"}{" "}
          ({r.sections_analysees}/{r.sections_totales} sections)
        </div>
        {r.analyse_globale ? (
          <p className="panel-sub" style={{ marginTop: 6 }}>
            Le document entier est pris en compte. Les notes suivent un barème
            explicite mais restent indicatives : relisez chaque remarque.
          </p>
        ) : (
          <p className="panel-sub" style={{ marginTop: 6 }}>
            Ce résultat ancien a été produit section par section. Relancez la
            vérification pour utiliser l’analyse globale corrigée.
          </p>
        )}
        {(r.sections || []).map((s, i) => (
          <div key={i} style={{ marginBottom: 12 }}>
            <strong>{s.titre}</strong>{" "}
            {s.analyse?.score_completude != null && (
              <span className="score-pill">{s.analyse.score_completude}/10</span>
            )}
            {s.analyse?.erreur_analyse && (
              <div className="error-box" style={{ marginTop: 8 }}>
                {s.analyse.erreur_analyse}
              </div>
            )}
            {s.analyse?.justification_score && (
              <p style={{ margin: "6px 0" }}>{s.analyse.justification_score}</p>
            )}
            {(s.analyse?.points_forts || []).length > 0 && (
              <ul className="result-list checklist-ok">
                {s.analyse.points_forts.map((a, j) => (
                  <li key={j}>Point fort : {a}</li>
                ))}
              </ul>
            )}
            {(s.analyse?.ambiguites || []).length > 0 && (
              <ul className="result-list">
                {s.analyse.ambiguites.map((a, j) => (
                  <li key={j}>Ambiguïté : {a}</li>
                ))}
              </ul>
            )}
            {(s.analyse?.informations_manquantes || []).length > 0 && (
              <ul className="result-list">
                {s.analyse.informations_manquantes.map((a, j) => (
                  <li key={j}>Manque : {a}</li>
                ))}
              </ul>
            )}
            {(s.analyse?.questions_previsibles_prestataire || []).length > 0 && (
              <ul className="result-list checklist-bad">
                {s.analyse.questions_previsibles_prestataire.map((a, j) => (
                  <li key={j}>Question prévisible : {a}</li>
                ))}
              </ul>
            )}
          </div>
        ))}
      </div>
    );
  }

  return <pre className="raw">{JSON.stringify(r, null, 2)}</pre>;
}
