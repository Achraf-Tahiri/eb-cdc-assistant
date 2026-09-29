export const STATE_LABELS = {
  BROUILLON: "Brouillon",
  SOUMIS_PROJET: "Soumis au projet",
  CORRECTION_METIER: "Correction métier",
  VALIDE_PROJET: "Validé projet",
  TRANSMIS_PRESTATAIRE: "Chez le prestataire",
  CLARIFICATION_DEMANDEE: "Clarification demandée",
  IMPLEMENTATION_ACCEPTEE: "Implémentation acceptée",
  CLOTURE: "Clôturé",
};

export default function StateBadge({ state }) {
  return (
    <span className={`badge badge-${state}`}>
      {STATE_LABELS[state] || state}
    </span>
  );
}
