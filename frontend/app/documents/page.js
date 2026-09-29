"use client";

import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import { api, getSession } from "../../lib/api";
import StateBadge from "../../components/StateBadge";

export default function DocumentsPage() {
  const router = useRouter();
  const [session, setSessionState] = useState(null);
  const [docs, setDocs] = useState([]);
  const [providers, setProviders] = useState([]);
  const [showCreate, setShowCreate] = useState(false);
  const [error, setError] = useState("");
  const [form, setForm] = useState({
    title: "",
    docType: "EB",
    projectCode: "",
    providerId: "",
  });

  useEffect(() => {
    const s = getSession();
    if (!s) {
      router.push("/");
      return;
    }
    setSessionState(s);
    load();
  }, []);

  async function load() {
    try {
      setDocs(await api("/api/documents"));
      setProviders(await api("/api/providers"));
    } catch (e) {
      setError(e.message);
    }
  }

  async function create() {
    setError("");
    try {
      const doc = await api("/api/documents", {
        method: "POST",
        body: JSON.stringify({
          title: form.title,
          docType: form.docType,
          projectCode: form.projectCode,
          providerId: form.providerId || null,
        }),
      });
      router.push(`/documents/${doc.id}`);
    } catch (e) {
      setError(e.message);
    }
  }

  async function deleteDraft(e, d) {
    e.stopPropagation(); // ne pas ouvrir le document
    if (!window.confirm(`Supprimer définitivement le brouillon « ${d.title} » ?`)) return;
    setError("");
    try {
      await api(`/api/documents/${d.id}`, { method: "DELETE" });
      load();
    } catch (err) {
      setError(err.message);
    }
  }

  const isMetier = session?.user?.role === "METIER";

  return (
    <div>
      <h1 className="page-title">Documents</h1>
      <p className="page-sub">
        Expressions de besoin et cahiers des charges, avec leur état de workflow.
      </p>
      {error && <div className="error-box">{error}</div>}

      {isMetier && (
        <div className="panel">
          <div className="btn-row">
            <button
              className="btn btn-secondary"
              onClick={() => setShowCreate(!showCreate)}
            >
              {showCreate ? "Annuler" : "Nouveau document"}
            </button>
          </div>
          {showCreate && (
            <div style={{ marginTop: 16 }}>
              <div className="form-row">
                <div className="field">
                  <label>Titre</label>
                  <input
                    value={form.title}
                    onChange={(e) => setForm({ ...form, title: e.target.value })}
                    placeholder="Refonte du module facturation"
                  />
                </div>
                <div className="field">
                  <label>Type</label>
                  <select
                    value={form.docType}
                    onChange={(e) => setForm({ ...form, docType: e.target.value })}
                  >
                    <option value="EB">Expression de besoin</option>
                    <option value="CDC">Cahier des charges</option>
                  </select>
                </div>
              </div>
              <div className="form-row">
                <div className="field">
                  <label>Code projet</label>
                  <input
                    value={form.projectCode}
                    onChange={(e) =>
                      setForm({ ...form, projectCode: e.target.value })
                    }
                    placeholder="FACT-2026"
                  />
                </div>
                <div className="field">
                  <label>Prestataire TMA</label>
                  <select
                    value={form.providerId}
                    onChange={(e) =>
                      setForm({ ...form, providerId: e.target.value })
                    }
                  >
                    <option value="">— À définir —</option>
                    {providers.map((p) => (
                      <option key={p.id} value={p.id}>
                        {p.name} ({p.contractRef})
                      </option>
                    ))}
                  </select>
                </div>
              </div>
              <button
                className="btn btn-accent"
                onClick={create}
                disabled={!form.title.trim()}
              >
                Créer le document
              </button>
            </div>
          )}
        </div>
      )}

      <div className="panel">
        {docs.length === 0 ? (
          <p className="empty">
            Aucun document pour le moment. Créez le premier pour lancer le workflow.
          </p>
        ) : (
          <table>
            <thead>
              <tr>
                <th>Titre</th>
                <th>Type</th>
                <th>Projet</th>
                <th>Prestataire</th>
                <th>État</th>
                <th></th>
              </tr>
            </thead>
            <tbody>
              {docs.map((d) => (
                <tr
                  key={d.id}
                  className="clickable"
                  onClick={() => router.push(`/documents/${d.id}`)}
                >
                  <td>{d.title}</td>
                  <td>{d.docType}</td>
                  <td>{d.projectCode || "—"}</td>
                  <td>{d.provider || "—"}</td>
                  <td>
                    <StateBadge state={d.state} />
                  </td>
                  <td>
                    {isMetier && d.state === "BROUILLON" && (
                      <button
                        className="btn btn-danger"
                        style={{ padding: "4px 10px", fontSize: 13 }}
                        title="Supprimer ce brouillon (irréversible)"
                        onClick={(e) => deleteDraft(e, d)}
                      >
                        Supprimer
                      </button>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>
    </div>
  );
}
