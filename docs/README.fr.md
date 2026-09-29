> [English overview](../README.md) · Guide technique en français

# Assistant RAG métier — Workflows EB/CDC

> Audit actualisé le 09/09/2026 : [résultats, corrections et limites](AUDIT.md).
> Pour comprendre et refaire les manipulations : [guide pédagogique](COMPRENDRE-ET-TESTER.md).

Application complète : workflow EB/CDC à trois rôles (métier / projet / prestataire),
journal d'audit horodaté, moteur RAG local (Ollama), analytique de détection des
patterns de ralentissement des prestataires TMA.

## Démarrage rapide

```bash
./demarrer.sh    # lance tout (base, backend, frontend) avec vérifications
./arreter.sh     # arrête tout proprement
```

Puis ouvrir http://localhost:3000 en **navigation privée** (Ctrl+Maj+P).
En cas d'échec, le script indique quel fichier de log consulter (`backend.log`, `frontend.log`).


## Stack

| Couche | Techno |
|---|---|
| Frontend | Next.js 15.5.25 / React 19 (App Router, JavaScript) |
| Backend | Java 21, Spring Boot 3.3 (Web, JPA, Security/JWT) |
| Données | PostgreSQL 16 + pgvector (Docker) |
| LLM / Embeddings | Ollama : `qwen3:4b` + `nomic-embed-text` |

## Prérequis

- Java 21 + Maven 3.8.7 ou supérieur
- Node.js 18.18+ (audit effectué avec Node 24)
- Docker + Docker Compose
- Ollama récent (l'API `/api/embed` et le paramètre `think` sont utilisés), modèles tirés :

```bash
ollama pull qwen3:4b
ollama pull nomic-embed-text
```

## Démarrage (3 terminaux)

```bash
# 1. Base de données (PostgreSQL + pgvector sur le port 5433)
docker compose up -d

# 2. Backend (port 8080) — le schéma SQL et les comptes démo sont créés au démarrage
cd backend
mvn spring-boot:run

# 3. Frontend (port 3000)
cd frontend
npm install
npm run dev
```

Ouvrir http://localhost:3000

### Jeu de données de démonstration (recommandé)

Après le premier démarrage du backend, pour avoir une analytique parlante
immédiatement (3 documents, une clarification tardive à 12 jours, un document
dormant, un rapport de complétude) :

```bash
psql "postgresql://ebcdc:ebcdc@localhost:5433/ebcdc" -f scripts/seed-demo.sql
```

Le script est idempotent : le relancer ne duplique rien.

## Comptes de démonstration

| Rôle | Email | Mot de passe |
|---|---|---|
| Métier | metier@demo.fr | demo1234 |
| Projet | projet@demo.fr | demo1234 |
| Prestataire (TMA Alpha) | prestataire@demo.fr | demo1234 |

## Tour de l'application

- **Documents** : liste et création (métier). Chaque document est en Markdown,
  versionné, avec un modèle pré-rempli des sections obligatoires.
- **Page document** : éditeur, boutons d'action de workflow (pilotés par le
  backend selon l'état et le rôle), journal d'audit, clarifications
  prestataire (jours depuis réception calculés automatiquement, tardives en
  rouge), panneau RAG (complétude + suggestions, jobs asynchrones avec suivi).
- **Corpus** (interne) : ingestion des anciens EB/CDC — fichier docx/pdf/txt/md
  via Apache Tika ou texte collé — et test de la recherche sémantique.
- **Analytique** (interne) : alertes automatiques, métriques M1-M4 par
  prestataire avec export CSV, décomposition M5 du temps par acteur,
  tableau M6 complétude ↔ clarifications.

### Scénario de démonstration manuel

1. **Métier** : créer un EB, rédiger, lancer **Vérifier la complétude**
   (rapport : ambiguïtés, informations manquantes, questions prévisibles du
   prestataire), puis **Soumettre**.
2. **Projet** : valider, puis **Transmettre au prestataire**.
3. **Prestataire** : poser une demande de clarification.
4. **Projet/Métier** : répondre → le document repart chez le prestataire.
5. **Prestataire** : accepter. **Projet** : clôturer, puis ouvrir **Analytique**.

## Métriques et alertes

- **M1** — délai moyen d'attente chez le prestataire (jours)
- **M2** — % de clarifications tardives (> 5 jours après réception,
  paramétrable : `app.rag.late-clarification-days`)
- **M3** — cycles de clarification moyens par document
- **M4** — ratio cycles/questions (1,00 = questions au compte-gouttes)
- **M5** — décomposition du temps par acteur pour un document
- **M6** — corrélation score de complétude ↔ cycles de clarification
- **Alertes** — document sans action prestataire depuis N jours, clarification
  tardive, 3ᵉ cycle de clarification atteint
- **Export CSV** — métriques par prestataire (séparateur `;`, BOM UTF-8,
  prêt pour Excel) pour les revues de contrat TMA

Ces métriques sont des **indicateurs d'objectivation des délais** pour les
comités de suivi TMA, pas des preuves d'intention.

## RAG — fonctionnement

- Découpage par sections Markdown puis chunks ~1200 caractères (chevauchement
  200), embeddings `nomic-embed-text` (768 dim), stockage pgvector, index HNSW
  cosinus.
- **Suggestions** : recherche de passages dans le corpus, rejet sous le seuil de
  similarité `app.rag.min-similarity-score`, puis génération par `qwen3:4b`.
  Le modèle reçoit aussi le document complet. Le serveur rejette les références
  inexistantes, les textes insuffisamment soutenus par leurs sources et les
  reformulations déjà présentes. Les noms et scores affichés viennent de la base,
  jamais du texte libre généré par le modèle.
- Réimporter une source avec le même nom remplace ses anciens passages dans une
  transaction, ce qui évite de surpondérer accidentellement un document.
- **Complétude** : couche 1 déterministe (sections obligatoires, sections courtes
  à vérifier) + couche 2 LLM globale. Le modèle reçoit le document entier et
  évalue jusqu'à `app.rag.max-llm-sections` sections avec un barème explicite.
- Les analyses de complétude et suggestions passent par la file `rag_jobs`, dépilée par un worker
  planifié — **un seul job à la fois**, adapté à une machine CPU sans GPU.
  L’ingestion et la recherche sont synchrones. Timeout de lecture Ollama : `app.ollama.read-timeout-seconds` (600 s par
  défaut).

## Notes techniques

- Machine à états implémentée à la main (`WorkflowService`) : table
  `(état, action) → (état cible, rôles autorisés)`, verrou pessimiste sur le
  document, audit écrit dans la même transaction, contrôle que le prestataire
  n'agit que sur les documents de son contrat.
- `workflow_transitions` est en écriture seule (aucune API de modification).
- `clarification_requests.days_since_provider_received` est calculé à
  l'insertion depuis la dernière transition vers `TRANSMIS_PRESTATAIRE`.
- Visibilité : un prestataire ne voit que les documents de son contrat, et
  uniquement à partir de la transmission ; corpus et analytique sont réservés
  aux rôles internes.

## État de vérification

| Élément | Statut |
|---|---|
| `schema.sql` (tables, index HNSW) | ✅ exécuté sur PostgreSQL 16 + pgvector 0.6 |
| `scripts/seed-demo.sql` | ✅ exécuté deux fois (idempotence vérifiée) |
| Requêtes analytiques M1-M6 + alertes | ✅ exécutées sur données de démo, valeurs contrôlées |
| Insertion/recherche pgvector (motifs SQL exacts du code Java) | ✅ score 1,0 sur vecteur identique |
| Frontend | ✅ `next build` sans erreur (6 routes) |
| Backend Java | ✅ compilation et 230 tests Java réussis ; 21 scénarios HTTP réussis (voir audit) |

Les scripts refusent désormais de démarrer sur un port occupé et n’arrêtent que leurs propres processus. Le seed de démonstration est explicite : commande SQL ci-dessus, ou `EBCDC_SEED_DEMO=1 ./demarrer.sh` pour la base standard `ebcdc`.
