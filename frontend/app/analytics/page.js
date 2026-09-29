"use client";

import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import { api, API, getSession } from "../../lib/api";
import StateBadge from "../../components/StateBadge";

const ALERT_LABELS = {
  DOCUMENT_DORMANT: "Document sans action prestataire",
  CLARIFICATION_TARDIVE: "Clarification tardive",
  CYCLES_REPETES: "Cycles répétés (≥ 3)",
};

export default function AnalyticsPage() {
  const router = useRouter();
  const [providers, setProviders] = useState([]);
  const [alerts, setAlerts] = useState([]);
  const [correlation, setCorrelation] = useState([]);
  const [docs, setDocs] = useState([]);
  const [selectedDoc, setSelectedDoc] = useState("");
  const [breakdown, setBreakdown] = useState([]);
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
    load();
  }, []);

  async function load() {
    try {
      setProviders(await api("/api/analytics/providers"));
      setAlerts(await api("/api/analytics/alerts"));
      setCorrelation(await api("/api/analytics/correlation"));
      setDocs(await api("/api/documents"));
    } catch (e) {
      setError(e.message);
    }
  }

  async function loadBreakdown(id) {
    setSelectedDoc(id);
    setBreakdown([]);
    if (!id) return;
    try {
      setBreakdown(await api(`/api/analytics/documents/${id}`));
    } catch (e) {
      setError(e.message);
    }
  }

  async function downloadCsv() {
    setError("");
    try {
      const res = await fetch(`${API}/api/analytics/providers.csv`, {
        headers: { Authorization: `Bearer ${getSession()?.token}` },
      });
      if (!res.ok) throw new Error(`Export impossible (erreur ${res.status})`);
      const blob = await res.blob();
      const url = URL.createObjectURL(blob);
      const a = document.createElement("a");
      a.href = url;
      a.download = "metriques-prestataires.csv";
      a.click();
      URL.revokeObjectURL(url);
    } catch (e) {
      setError(e.message);
    }
  }

  const maxDays = Math.max(1, ...breakdown.map((b) => Number(b.jours) || 0));

  return (
    <div>
      <h1 className="page-title">Analytique des délais</h1>
      <p className="page-sub">
        Indicateurs d'objectivation des délais pour les comités de suivi TMA — des
        signaux à instruire, pas des preuves d'intention.
      </p>
      {error && <div className="error-box">{error}</div>}

      {alerts.length > 0 && (
        <div className="panel">
          <h2>Alertes</h2>
          <p className="panel-sub">Signaux détectés automatiquement sur le journal d'audit.</p>
          {alerts.map((a, i) => (
            <div key={i} className="clarif">
              <div className="meta">
                <span className="late">{ALERT_LABELS[a.type] || a.type}</span>
                {" · "}{a.prestataire}
                {a.jours != null && ` · ${a.jours} j`}
                {a.cycles != null && ` · ${a.cycles} cycles`}
              </div>
              <div>
                {a.titre}
                {a.question ? ` — « ${a.question} »` : ""}
              </div>
            </div>
          ))}
        </div>
      )}

      <div className="panel">
        <h2>Par prestataire</h2>
        <p className="panel-sub">
          M1 : attente moyenne · M2 : % de clarifications tardives (seuil configuré) ·
          M3 : cycles par document · M4 : cycles / questions (1,00 = questions au
          compte-gouttes, une par cycle).
        </p>
        <table>
          <thead>
            <tr>
              <th>Prestataire</th>
              <th className="num">Docs</th>
              <th className="num">M1 (jours)</th>
              <th className="num">M2 (%)</th>
              <th className="num">M3</th>
              <th className="num">M4</th>
              <th className="num">Questions</th>
            </tr>
          </thead>
          <tbody>
            {providers.map((p) => (
              <tr key={p.provider_id}>
                <td>
                  {p.name}
                  <div style={{ fontSize: 12, color: "var(--muted)" }}>
                    {p.contract_ref}
                  </div>
                </td>
                <td className="num">{p.nb_documents}</td>
                <td className="num">{p.m1_attente_moyenne_jours}</td>
                <td className="num">{p.m2_taux_tardif_pct}</td>
                <td className="num">{p.m3_cycles_par_document}</td>
                <td className="num">{p.m4_ratio_cycles_questions ?? "—"}</td>
                <td className="num">{p.total_questions}</td>
              </tr>
            ))}
          </tbody>
        </table>
        <div className="btn-row" style={{ marginTop: 14 }}>
          <button className="btn btn-secondary" onClick={downloadCsv}>
            Exporter en CSV
          </button>
        </div>
      </div>

      <div className="panel">
        <h2>Temps par acteur (M5)</h2>
        <p className="panel-sub">
          Décomposition du délai d'un document : la métrique qui montre aussi quand
          le retard vient de l'interne.
        </p>
        <div className="field" style={{ maxWidth: 420 }}>
          <label>Document</label>
          <select
            value={selectedDoc}
            onChange={(e) => loadBreakdown(e.target.value)}
          >
            <option value="">— Choisir un document —</option>
            {docs.map((d) => (
              <option key={d.id} value={d.id}>
                {d.title}
              </option>
            ))}
          </select>
        </div>
        {breakdown.length > 0 && (
          <table style={{ maxWidth: 640 }}>
            <thead>
              <tr>
                <th>Acteur</th>
                <th className="num">Jours</th>
                <th style={{ width: "45%" }}></th>
              </tr>
            </thead>
            <tbody>
              {breakdown.map((b, i) => (
                <tr key={i}>
                  <td>{b.acteur}</td>
                  <td className="num">{b.jours}</td>
                  <td>
                    <div className="bar-track">
                      <div
                        className="bar-fill"
                        style={{
                          width: `${(100 * (Number(b.jours) || 0)) / maxDays}%`,
                        }}
                      />
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>

      <div className="panel">
        <h2>Complétude vs clarifications (M6)</h2>
        <p className="panel-sub">
          Comparaison descriptive du score IA et des clarifications. Une note élevée
          ne prouve pas l’absence d’ambiguïté ni la responsabilité du prestataire.
        </p>
        <table>
          <thead>
            <tr>
              <th>Document</th>
              <th>Prestataire</th>
              <th>État</th>
              <th className="num">Score complétude</th>
              <th className="num">Cycles</th>
              <th className="num">Questions</th>
            </tr>
          </thead>
          <tbody>
            {correlation.map((c) => (
              <tr key={c.documentId}>
                <td>{c.titre}</td>
                <td>{c.prestataire || "—"}</td>
                <td>
                  <StateBadge state={c.etat} />
                </td>
                <td className="num">
                  {c.score_completude != null ? `${c.score_completude}/10` : "—"}
                </td>
                <td className="num">{c.cycles_clarification}</td>
                <td className="num">{c.questions}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}
