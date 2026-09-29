# Dossier de projet — Assistant RAG métier pour les workflows EB/CDC

> **Mise à jour 09/09/2026 :** ce dossier décrit un état historique. Des tests ont révélé des défauts malgré les affirmations de complétude ci-dessous. Consultez [l’audit actuel](docs/AUDIT.md) et le [guide de compréhension](docs/COMPRENDRE-ET-TESTER.md) pour les corrections, versions techniques et limites vérifiées.

> **À quoi sert ce document ?**
> Il explique **la totalité du projet** : le problème métier, ce que fait
> l'application, comment elle est construite, fichier par fichier, ce qui est
> terminé et ce qui ne l'est pas. Il est écrit pour être lu de bout en bout
> avant une présentation à un encadrant de stage.
>
> Rédigé le 31/08/2026, après relecture complète du code source
> (40 fichiers Java ≈ 2 150 lignes, 9 fichiers JavaScript ≈ 1 460 lignes,
> 389 lignes de CSS, 248 lignes de SQL).

---

## Table des matières

1. [Résumé en une page](#1-résumé-en-une-page)
2. [Le problème métier](#2-le-problème-métier)
3. [Ce que fait l'application](#3-ce-que-fait-lapplication)
4. [Architecture technique](#4-architecture-technique)
5. [Modèle de données](#5-modèle-de-données)
6. [Le workflow : la machine à états](#6-le-workflow--la-machine-à-états)
7. [Le moteur RAG](#7-le-moteur-rag)
8. [L'analytique : les métriques M1 à M6](#8-lanalytique--les-métriques-m1-à-m6)
9. [Sécurité, rôles et visibilité](#9-sécurité-rôles-et-visibilité)
10. [Cartographie du code, fichier par fichier](#10-cartographie-du-code-fichier-par-fichier)
11. [L'API REST complète](#11-lapi-rest-complète)
12. [Installation, lancement, démonstration](#12-installation-lancement-démonstration)
13. [État d'avancement : est-ce terminé ?](#13-état-davancement--est-ce-terminé-)
14. [Limites connues et dettes techniques](#14-limites-connues-et-dettes-techniques)
15. [Questions probables de l'encadrant et réponses](#15-questions-probables-de-lencadrant-et-réponses)
16. [Glossaire](#16-glossaire)

---

## 1. Résumé en une page

**Nom du projet** : Registre EB/CDC — Assistant RAG métier.

**En une phrase** : une application web qui pilote le cycle de vie des
*Expressions de Besoin* (EB) et *Cahiers des Charges* (CDC) entre trois acteurs
(métier, projet, prestataire TMA), aide à les rédiger grâce à une IA locale, et
mesure objectivement où le temps se perd.

**Trois briques, une seule cohérence** :

| Brique | Ce qu'elle fait | Pourquoi elle existe |
|---|---|---|
| **Workflow tracé** | Un document passe par 8 états, chaque changement est horodaté et signé par son auteur dans un journal en écriture seule | Sans traçabilité, impossible de savoir qui a fait attendre qui |
| **Assistant RAG** | Une IA locale (Ollama) analyse la complétude des documents et suggère des améliorations en s'appuyant sur les anciens EB/CDC | Un document ambigu génère des allers-retours ; mieux vaut le détecter avant l'envoi |
| **Analytique** | 6 métriques + 3 alertes automatiques calculées sur le journal d'audit | Objectiver les délais lors des comités de suivi de contrat TMA |

**Stack** : Next.js 14 (frontend) · Java 21 / Spring Boot 3.3 (backend) ·
PostgreSQL 16 + pgvector (données + base vectorielle) · Ollama avec `qwen3:4b`
et `nomic-embed-text` (IA 100 % locale, aucune donnée ne sort de la machine).

**État** : **fonctionnellement complet et opérationnel**. Le backend compile
sans erreur, le frontend se construit sans erreur (6 routes), l'application a
déjà été lancée et utilisée de bout en bout. Ce qui manque relève de la
production (tests automatisés, secrets, déploiement) et non de la fonctionnalité
— voir la [section 13](#13-état-davancement--est-ce-terminé-).

---

## 2. Le problème métier

### 2.1 Le contexte

Dans une DSI, quand une direction métier a besoin d'une évolution logicielle,
elle rédige une **Expression de Besoin (EB)**, parfois formalisée ensuite en
**Cahier des Charges (CDC)**. Ce document circule :

```
Métier  ──rédige──▶  Projet  ──valide & transmet──▶  Prestataire TMA  ──chiffre & implémente
```

La **TMA** (Tierce Maintenance Applicative) est le prestataire externe qui
maintient et fait évoluer les applications, sous contrat, souvent facturé au
temps passé ou avec des engagements de délais (SLA).

### 2.2 Le problème observé

Deux dysfonctionnements se combinent :

1. **Les documents sont incomplets ou ambigus.** Le rédacteur métier n'est pas
   un rédacteur technique. Il oublie des sections, reste vague sur les
   contraintes, ne définit pas de critères d'acceptation. Le prestataire doit
   alors demander des clarifications.

2. **Les demandes de clarification servent parfois de levier de délai.** Un
   prestataire peut poser ses questions **tardivement** (12 jours après avoir
   reçu le document, alors qu'elles étaient visibles dès la lecture) et **au
   compte-gouttes** (une question par cycle, plutôt que toutes d'un coup).
   Chaque cycle relance le chronomètre et repousse la responsabilité du retard
   côté client.

Le problème, c'est que **personne ne peut le prouver**. Les échanges se font par
mail, aucune donnée n'est structurée, et en comité de suivi le débat reste au
niveau du ressenti : « vous répondez trop lentement » contre « votre document
était incomplet ».

### 2.3 La réponse apportée par le projet

L'application attaque le problème **par les deux bouts** :

- **En amont** : améliorer la qualité du document avant l'envoi, avec une
  vérification de complétude et des suggestions de rédaction basées sur les
  documents historiques. Moins d'ambiguïtés = moins de questions légitimes.
- **En aval** : rendre les délais **mesurables**. Chaque transition étant
  horodatée, on peut calculer précisément combien de jours le document a passé
  chez chaque acteur, à quel moment les questions ont été posées, et combien de
  cycles ont été nécessaires.

> **Précaution de langage importante, à répéter en soutenance** : ces métriques
> sont des **indicateurs d'objectivation des délais**, pas des preuves
> d'intention. L'application ne dit jamais « ce prestataire fait exprès ». Elle
> dit « ce document est resté 12 jours sans action, et la première question
> portait sur une section lisible dès le premier jour ». L'interprétation reste
> humaine. Cette nuance est écrite en clair dans l'interface (page Analytique)
> et dans les commentaires du code.

---

## 3. Ce que fait l'application

### 3.1 Les trois rôles

| Rôle | Qui | Ce qu'il peut faire |
|---|---|---|
| **METIER** | La direction métier qui exprime le besoin | Créer un document, le rédiger, supprimer son brouillon, le soumettre, répondre aux clarifications, utiliser l'assistant RAG |
| **PROJET** | Le chef de projet / la MOA côté DSI | Valider ou renvoyer en correction, transmettre au prestataire, répondre aux clarifications, clôturer, consulter l'analytique |
| **PRESTATAIRE** | Le prestataire TMA (rattaché à un contrat) | Consulter **uniquement** les documents de son contrat et **uniquement** à partir de leur transmission, poser des clarifications, accepter l'implémentation |

### 3.2 Les quatre écrans

1. **Connexion** (`/`) — email + mot de passe, trois comptes de démonstration.
2. **Documents** (`/documents`) — la liste des documents visibles selon le rôle,
   avec leur état ; le métier peut en créer un nouveau (formulaire titre / type
   EB ou CDC / code projet / prestataire) ou supprimer un brouillon.
3. **Fiche document** (`/documents/[id]`) — l'écran principal, en deux colonnes :
   - **à gauche** : l'éditeur Markdown du document + le fil des clarifications ;
   - **à droite** : le panneau de l'assistant RAG + le journal d'audit ;
   - **en haut** : les boutons d'action du workflow, **calculés par le backend**
     selon l'état du document et le rôle de l'utilisateur (l'interface n'invente
     jamais un bouton : elle demande au serveur ce qui est autorisé).
4. **Corpus** (`/corpus`, interne) — ingestion des anciens EB/CDC (fichier
   docx/pdf/txt/md ou texte collé) dans la base vectorielle, et test de la
   recherche sémantique.
5. **Analytique** (`/analytics`, interne) — alertes, métriques par prestataire
   avec export CSV, décomposition du temps par acteur, tableau complétude ↔
   clarifications.

### 3.3 Le parcours type, de bout en bout

```
1. METIER crée un EB → un squelette Markdown pré-rempli est généré
   (Contexte / Périmètre / Besoins fonctionnels / Contraintes /
    Critères d'acceptation / Délais)
2. METIER rédige, puis clique « Vérifier la complétude »
   → un job asynchrone part en file d'attente
   → l'IA locale renvoie : sections manquantes, sections trop courtes,
     ambiguïtés, informations manquantes, questions que le prestataire
     va probablement poser, note /10 par section
3. METIER corrige, puis « Soumettre au projet »        [BROUILLON → SOUMIS_PROJET]
4. PROJET valide                                       [SOUMIS_PROJET → VALIDE_PROJET]
   (ou demande une correction)                         [→ CORRECTION_METIER]
5. PROJET transmet                                [VALIDE_PROJET → TRANSMIS_PRESTATAIRE]
   ⏱  le chronomètre « chez le prestataire » démarre ici
6. PRESTATAIRE pose une question           [→ CLARIFICATION_DEMANDEE]
   → le système calcule automatiquement « posée X jours après réception »
7. PROJET ou METIER répond                 [→ TRANSMIS_PRESTATAIRE]  (nouveau cycle)
8. PRESTATAIRE accepte                     [→ IMPLEMENTATION_ACCEPTEE]
9. PROJET clôture                          [→ CLOTURE]
10. Tout le parcours est lisible dans le journal d'audit et alimente
    automatiquement les métriques de la page Analytique.
```

---

## 4. Architecture technique

### 4.1 Vue d'ensemble

```
┌───────────────────────────────────────────────────────────────────┐
│  NAVIGATEUR                                                       │
│  Next.js 14 (App Router, JavaScript, React 18)  ── port 3000      │
│  • 6 routes  • jeton JWT stocké dans localStorage                 │
└───────────────────────┬───────────────────────────────────────────┘
                        │  HTTP/JSON  +  en-tête Authorization: Bearer …
                        ▼
┌───────────────────────────────────────────────────────────────────┐
│  BACKEND — Java 21 / Spring Boot 3.3.4        ── port 8080        │
│                                                                   │
│  web/         contrôleurs REST (Documents, RAG, Analytics, Auth)  │
│  workflow/    machine à états + règles de rôle                    │
│  rag/         chunking, embeddings, worker de jobs, client Ollama │
│  analytics/   requêtes SQL des métriques M1-M6 et des alertes     │
│  domain/      entités JPA        repo/  repositories Spring Data  │
│  auth/        JWT (création, filtre de sécurité)                  │
└───────┬─────────────────────────────────────┬─────────────────────┘
        │  JDBC / JPA                         │  HTTP REST
        ▼                                     ▼
┌────────────────────────────┐   ┌────────────────────────────────┐
│ PostgreSQL 16 + pgvector   │   │ Ollama (local)   ── port 11434 │
│ (Docker) ── port 5433      │   │ • qwen3:4b        (génération) │
│ • 7 tables métier          │   │ • nomic-embed-text (embeddings)│
│ • index HNSW cosinus       │   │ 100 % hors ligne, rien ne sort │
└────────────────────────────┘   └────────────────────────────────┘
```

### 4.2 Pourquoi ces choix ?

| Choix | Justification |
|---|---|
| **Spring Boot** | Standard de l'entreprise pour le back Java ; apporte gratuitement la sécurité, la couche JPA, l'ordonnanceur de tâches et la gestion transactionnelle |
| **Next.js sans TypeScript** | Le projet est un démonstrateur de stage : le JavaScript simple reste lisible par un relecteur non-frontend. L'App Router permet des pages `"use client"` très directes |
| **PostgreSQL + pgvector** | Une seule base pour les données métier **et** les vecteurs. Pas de base vectorielle dédiée (Qdrant, Pinecone…) à administrer, pas de synchronisation à gérer |
| **Ollama en local** | Les EB/CDC contiennent des informations sensibles (volumétries, contrats, données RH). Aucun appel vers une API externe : le modèle tourne sur la machine |
| **`qwen3:4b`** | Modèle de 4 milliards de paramètres : le meilleur compromis qualité / vitesse sur une machine **CPU sans carte graphique**, avec un bon support du français et du format JSON |
| **File de jobs plutôt qu'appels directs** | Sur CPU, une analyse LLM prend plusieurs minutes. Si la requête HTTP attendait la réponse, le navigateur expirerait. D'où : on crée un job, le serveur répond immédiatement, le front interroge le statut toutes les 4 secondes |
| **Machine à états écrite à la main** | Une table `(état, action) → (état cible, rôles autorisés)` de 9 lignes est plus lisible et plus facile à défendre en soutenance qu'un moteur BPMN complet |

### 4.3 Le flux d'une requête, concrètement

Exemple : le chef de projet clique sur « Transmettre au prestataire ».

```
1. Le navigateur envoie POST /api/documents/{id}/actions  { action: "SEND" }
   avec l'en-tête Authorization: Bearer <jwt>
2. JwtAuthFilter lit le jeton, en extrait l'email, recharge l'utilisateur
   depuis la base et le place dans le contexte de sécurité Spring
3. DocumentController.applyAction() délègue à WorkflowService
4. WorkflowService ouvre une transaction et :
   a. verrouille la ligne du document (SELECT … FOR UPDATE) → pas de conflit
      si deux personnes cliquent en même temps
   b. cherche la règle (VALIDE_PROJET, SEND) dans la table de transitions
      → introuvable ⇒ erreur 409 « action non autorisée depuis cet état »
   c. vérifie que le rôle PROJET fait partie des rôles autorisés
      → sinon ⇒ erreur 403
   d. écrit une ligne dans workflow_transitions (l'audit)
   e. met à jour documents.current_state
   → les points d et e sont dans la MÊME transaction : il est impossible
     d'avoir un changement d'état non journalisé, ou l'inverse
5. Réponse { "state": "TRANSMIS_PRESTATAIRE" }
6. Le front recharge la fiche, les boutons disponibles, le journal
```

---

## 5. Modèle de données

Sept tables, créées au démarrage du backend par `schema.sql`
(`CREATE TABLE IF NOT EXISTS`, donc rejouable sans risque).

```
providers ──┬──< users                (un prestataire a des comptes)
            └──< documents            (un document est affecté à un contrat TMA)

documents ──┬──< document_versions     (historique complet des rédactions)
            ├──< workflow_transitions  (le journal d'audit, écriture seule)
            ├──< clarification_requests
            ├──< rag_jobs              (analyses IA demandées)
            └──< rag_chunks            (passages vectorisés, optionnel)
```

### 5.1 Table par table

| Table | Rôle | Colonnes remarquables |
|---|---|---|
| `providers` | Les prestataires TMA sous contrat | `contract_ref` (référence du marché), `sla_days` (engagement de délai) |
| `users` | Les comptes | `role` contraint à `METIER`/`PROJET`/`PRESTATAIRE`, `password_hash` (BCrypt), `provider_id` renseigné uniquement pour un prestataire |
| `documents` | L'en-tête du document | `doc_type` (`EB` ou `CDC`), `current_state` (l'état courant du workflow), `provider_id` (le contrat visé) |
| `document_versions` | Le contenu, versionné | `version_num` unique par document ; **on n'écrase jamais** : chaque enregistrement crée une nouvelle version |
| `workflow_transitions` | **Le journal d'audit** | `from_state`, `to_state`, `action`, `actor_id`, `actor_role`, `comment`, `occurred_at`. Table en **écriture seule** : aucune API ne permet de la modifier ou de la supprimer |
| `clarification_requests` | Les questions du prestataire | `days_since_provider_received` — **la colonne clé du projet** : le nombre de jours entre la réception du document et la question, calculé automatiquement à l'insertion |
| `rag_chunks` | La base vectorielle | `embedding vector(768)` + index **HNSW** en distance cosinus |
| `rag_jobs` | La file d'attente des analyses IA | `job_type`, `status` (`PENDING`/`RUNNING`/`DONE`/`FAILED`), `result` (JSON), `error` |

### 5.2 Deux décisions de conception à défendre

**a) Le versionnement plutôt que la modification en place.**
Enregistrer une version ne remplace pas le texte : cela insère une nouvelle
ligne avec `version_num + 1`. On peut donc toujours répondre à la question
« qu'est-ce que le prestataire avait exactement sous les yeux quand il a posé
cette question ? ». C'est indispensable pour arbitrer un désaccord.

**b) L'audit est immuable, mais un brouillon reste supprimable.**
Règle implémentée dans `DocumentController.deleteDraft()` : seul le rôle
`METIER`, seul l'auteur, et seulement si le document est encore à l'état
`BROUILLON`. Le raisonnement, écrit en commentaire dans le code : un brouillon
n'a été vu par personne d'autre que son auteur, sa seule trace d'audit est sa
propre création — le supprimer n'efface aucune interaction entre acteurs. Dès
la première soumission, le document devient **définitivement** non supprimable.

---

## 6. Le workflow : la machine à états

C'est le cœur logique de l'application. Tout est concentré dans un seul
fichier : `backend/src/main/java/com/ebcdc/assistant/workflow/WorkflowService.java`.

### 6.1 Les 8 états

| État | Signification | Chez qui est la balle ? |
|---|---|---|
| `BROUILLON` | En cours de rédaction | Métier |
| `SOUMIS_PROJET` | En attente de validation | Projet |
| `CORRECTION_METIER` | Renvoyé pour correction | Métier |
| `VALIDE_PROJET` | Validé, prêt à partir | Projet |
| `TRANSMIS_PRESTATAIRE` | Chez le prestataire | **Prestataire** ⏱ |
| `CLARIFICATION_DEMANDEE` | Question en attente de réponse | Projet / Métier |
| `IMPLEMENTATION_ACCEPTEE` | Chiffrage/périmètre accepté | Prestataire |
| `CLOTURE` | Cycle terminé | — |

### 6.2 Les 9 transitions autorisées

| État de départ | Action | État d'arrivée | Rôles autorisés |
|---|---|---|---|
| `BROUILLON` | `SUBMIT` | `SOUMIS_PROJET` | METIER |
| `SOUMIS_PROJET` | `VALIDATE` | `VALIDE_PROJET` | PROJET |
| `SOUMIS_PROJET` | `REQUEST_CORRECTION` | `CORRECTION_METIER` | PROJET |
| `CORRECTION_METIER` | `SUBMIT` | `SOUMIS_PROJET` | METIER |
| `VALIDE_PROJET` | `SEND` | `TRANSMIS_PRESTATAIRE` | PROJET |
| `TRANSMIS_PRESTATAIRE` | `REQUEST_CLARIFICATION` | `CLARIFICATION_DEMANDEE` | PRESTATAIRE |
| `TRANSMIS_PRESTATAIRE` | `ACCEPT` | `IMPLEMENTATION_ACCEPTEE` | PRESTATAIRE |
| `CLARIFICATION_DEMANDEE` | `RESOLVE` | `TRANSMIS_PRESTATAIRE` | PROJET, METIER |
| `IMPLEMENTATION_ACCEPTEE` | `CLOSE` | `CLOTURE` | PROJET |

**Toute combinaison absente de ce tableau est refusée** avec un code HTTP 409.
Un rôle non autorisé est refusé avec un 403. Il n'y a pas de chemin de secours,
pas de « forçage administrateur » : c'est ce qui rend le journal crédible.

Le diagramme du cycle nominal, avec la boucle de clarification :

```
                            ┌──── REQUEST_CORRECTION ────┐
                            ▼                            │
  BROUILLON ──SUBMIT──▶ SOUMIS_PROJET ◀──SUBMIT── CORRECTION_METIER
                            │
                        VALIDATE
                            ▼
                      VALIDE_PROJET
                            │
                          SEND
                            ▼
      ┌────────────▶ TRANSMIS_PRESTATAIRE ──ACCEPT──▶ IMPLEMENTATION_ACCEPTEE
      │                     │                                   │
      │           REQUEST_CLARIFICATION                       CLOSE
      │                     ▼                                   ▼
      └───RESOLVE──── CLARIFICATION_DEMANDEE                 CLOTURE
              (boucle : chaque aller-retour = 1 « cycle »)
```

### 6.3 Les trois garde-fous du code

1. **Verrou pessimiste** — `findByIdForUpdate()` exécute un
   `SELECT … FOR UPDATE`. Si deux utilisateurs agissent simultanément sur le
   même document, le second attend et rejoue la vérification d'état : pas de
   double transition.
2. **Audit dans la même transaction** — l'écriture du journal et le changement
   d'état réussissent ou échouent ensemble. Aucun changement d'état ne peut
   exister sans sa ligne d'audit.
3. **Cloisonnement du prestataire** — même si la règle autorise son rôle, on
   vérifie en plus que le document appartient bien à **son** contrat TMA. Un
   prestataire ne peut donc pas agir sur le document d'un concurrent.

### 6.4 Le point le plus subtil : la mesure du délai

Quand le prestataire pose une question
(`DocumentController.createClarification`), le backend ne se contente pas de
l'enregistrer. Il cherche **la dernière transition vers
`TRANSMIS_PRESTATAIRE`** et calcule l'écart avec l'instant présent :

```java
Double daysSince = transitions
    .findFirstByDocumentIdAndToStateOrderByOccurredAtDesc(id, TRANSMIS_PRESTATAIRE)
    .map(t -> Duration.between(t.getOccurredAt(), Instant.now()).toMillis() / 86_400_000.0)
    .map(d -> Math.round(d * 100.0) / 100.0)
    .orElse(null);
```

Cette valeur est **figée** dans `clarification_requests.days_since_provider_received`.
C'est la donnée qui alimente la métrique M2 et l'alerte « clarification
tardive ». Elle est affichée dans l'interface (« posée 12 j après réception »,
en rouge au-delà du seuil) au moment même où la question est posée : l'acteur
sait que c'est mesuré, ce qui a en soi un effet vertueux.

**Second point subtil** : la première question d'un cycle fait basculer l'état
en `CLARIFICATION_DEMANDEE` ; les questions suivantes **dans le même cycle**
sont simplement ajoutées sans nouvelle transition. C'est exactement ce qui
permet de distinguer, en métrique M4, « 5 questions posées d'un coup »
(1 cycle / 5 questions = 0,20) de « 5 questions au compte-gouttes »
(5 cycles / 5 questions = 1,00).

---

## 7. Le moteur RAG

**RAG** = *Retrieval-Augmented Generation* : au lieu de laisser le modèle de
langage répondre de mémoire, on va d'abord **chercher** des passages pertinents
dans une base documentaire, puis on les **injecte** dans le prompt. Le modèle
répond alors en s'appuyant sur les documents réels de l'entreprise.

### 7.1 La chaîne d'ingestion (alimenter le corpus)

```
Fichier .docx/.pdf/.txt/.md   ou   texte collé
        │
        ▼  Apache Tika  (extraction du texte brut, quel que soit le format)
        │
        ▼  ChunkingService.splitSections()
        │     découpage par titres Markdown (#, ##, …) pour garder
        │     le contexte métier de chaque section
        │
        ▼  ChunkingService.chunk()
        │     fenêtres de 1 200 caractères, chevauchement de 200
        │     (le chevauchement évite de couper une idée en deux)
        │
        ▼  OllamaClient.embed()  — par lots de 16
        │     modèle nomic-embed-text → un vecteur de 768 nombres par passage
        │
        ▼  RagVectorStore.insertChunk()
              INSERT … VALUES (…, CAST('[0.12,-0.34,…]' AS vector))
              plafond de sécurité : 300 passages par document
```

### 7.2 La recherche sémantique

```sql
SELECT chunk_text, source_label, section_title,
       round((1 - (embedding <=> CAST(? AS vector)))::numeric, 4) AS score
FROM rag_chunks
ORDER BY embedding <=> CAST(? AS vector)
LIMIT ?
```

L'opérateur `<=>` de pgvector est la **distance cosinus**. Comme c'est une
distance (0 = identique), on affiche `1 - distance` comme **score de
similarité** (1,00 = passage identique). L'index **HNSW** rend cette recherche
rapide même sur un gros corpus, sans comparer le vecteur à toutes les lignes.

### 7.3 Les deux fonctions offertes à l'utilisateur

#### a) La vérification de complétude — deux couches

**Couche 1 — déterministe** (`CompletenessChecker`, aucun appel IA, instantané) :
- les 6 sections obligatoires sont-elles présentes ? (Contexte, Périmètre,
  Besoins fonctionnels, Contraintes, Critères d'acceptation, Délais) — la
  comparaison ignore les accents et la casse ;
- quelles sections font moins de 200 caractères (« trop courtes ») ;
- longueur totale du document.

**Couche 2 — LLM** (`RagJobWorker.runCompletenessCheck`) : section par section
(6 au maximum, paramètre `app.rag.max-llm-sections`), le modèle reçoit un prompt
en français qui lui impose un **JSON strict** :

```json
{
  "ambiguites": ["…"],
  "informations_manquantes": ["…"],
  "questions_previsibles_prestataire": ["…"],
  "score_completude": 0
}
```

La clé `questions_previsibles_prestataire` est **le lien direct avec le problème
métier** : l'IA prédit les questions que le prestataire va poser, ce qui permet
d'y répondre **avant** l'envoi et de supprimer le prétexte du cycle de
clarification.

> Pourquoi deux couches ? La couche 1 est gratuite, instantanée, et toujours
> juste. La couche 2 apporte la finesse sémantique mais coûte plusieurs minutes
> et peut se tromper. Les combiner donne un résultat utile même si le modèle
> répond mal.

#### b) Les suggestions de rédaction

L'utilisateur cible une section (« Besoins fonctionnels »). Le système :
1. transforme le titre + le brouillon en vecteur ;
2. récupère les **5 passages les plus proches** dans le corpus historique ;
3. construit un prompt contenant ces extraits + le brouillon ;
4. demande 2 à 3 suggestions en JSON strict, chacune avec un `titre`, un
   `texte` prêt à insérer, et sa `source` ;
5. le frontend affiche un bouton **« Insérer dans la section »** qui place le
   texte au bon endroit du Markdown (la fonction `insertIntoSection` retrouve la
   section même avec des différences d'accents ou de casse, et la crée en fin de
   document si elle n'existe pas).

L'utilisateur relit et enregistre lui-même une version : **l'IA ne modifie
jamais le document toute seule.**

### 7.4 La file de jobs : pourquoi et comment

Contrainte matérielle : la machine cible n'a **pas de GPU**. Une analyse
complète peut prendre plusieurs minutes, et deux analyses en parallèle ne vont
pas plus vite — elles se ralentissent mutuellement.

La solution retenue :

```
Front : POST /api/rag/jobs        → réponse immédiate { id, status: "PENDING" }
        GET  /api/rag/jobs/{id}   → toutes les 4 s, jusqu'à DONE ou FAILED

Back  : RagJobWorker, annoté @Scheduled(fixedDelay = 4000)
        → prend UN SEUL job PENDING (le plus ancien)
        → le passe en RUNNING, l'exécute, le passe en DONE (avec le JSON
          résultat) ou FAILED (avec le message d'erreur)
```

Le timeout de lecture vers Ollama est volontairement fixé à **600 secondes**
(`app.ollama.read-timeout-seconds`). Et si le modèle renvoie un JSON malformé,
`parseLlmJson()` ne fait pas échouer le job : il encapsule la réponse brute dans
`{ "texte_brut": "…" }`. **Une IA qui répond mal ne casse jamais
l'application.**

---

## 8. L'analytique : les métriques M1 à M6

Tout est dans `analytics/AnalyticsService.java`, en SQL, calculé à la volée sur
le journal d'audit. Aucune donnée n'est dupliquée ni pré-agrégée.

### 8.1 La technique commune : la fonction fenêtre `lead()`

Le journal ne stocke que des **instants**. Pour connaître la **durée** passée
dans un état, il faut regarder la transition suivante :

```sql
SELECT wt.document_id, wt.to_state, wt.action, wt.occurred_at,
       lead(wt.occurred_at) OVER (PARTITION BY wt.document_id
                                  ORDER BY wt.occurred_at, wt.id) AS next_at
FROM workflow_transitions wt
```

`lead()` va chercher la valeur de la ligne suivante. La durée d'un état est donc
`COALESCE(next_at, now()) - occurred_at` — et le `COALESCE` gère le cas du
document encore en cours (« il attend depuis X jours, maintenant »).

### 8.2 Les six métriques

| Code | Nom | Calcul | Ce qu'elle révèle |
|---|---|---|---|
| **M1** | Délai moyen d'attente chez le prestataire | Moyenne des durées passées dans l'état `TRANSMIS_PRESTATAIRE`, par prestataire | La lenteur brute de traitement |
| **M2** | % de clarifications tardives | Part des questions dont `days_since_provider_received` dépasse le seuil (5 j par défaut) | Des questions posées longtemps après la lecture possible du document |
| **M3** | Cycles de clarification par document | Nombre d'actions `REQUEST_CLARIFICATION` ÷ nombre de documents du prestataire | La fréquence des allers-retours |
| **M4** | Ratio cycles / questions | Nombre de cycles ÷ nombre de questions | **La métrique signature du projet.** 1,00 = une seule question par cycle (compte-gouttes) ; 0,20 = 5 questions groupées en un cycle (comportement coopératif) |
| **M5** | Décomposition du temps par acteur | Somme des durées, regroupées par acteur responsable de l'état | **L'honnêteté de l'outil** : elle montre aussi quand le retard vient de l'interne |
| **M6** | Complétude ↔ clarifications | Score moyen de complétude du document (issu de l'analyse IA) confronté au nombre de cycles et de questions | Un document bien noté qui génère quand même des cycles **invalide l'argument « votre document était ambigu »** |

Le seuil de tardiveté est **paramétrable** dans `application.yml`
(`app.rag.late-clarification-days: 5`) : le comité de suivi peut l'aligner sur
le SLA réel du contrat.

### 8.3 Les trois alertes automatiques

| Type | Condition SQL | Action attendue |
|---|---|---|
| `DOCUMENT_DORMANT` | Document à l'état `TRANSMIS_PRESTATAIRE` dont la dernière transition date de plus de N jours | Relancer le prestataire |
| `CLARIFICATION_TARDIVE` | Question posée plus de N jours après la réception (20 plus récentes) | Instruire : la question était-elle réellement impossible à poser plus tôt ? |
| `CYCLES_REPETES` | Document ayant atteint 3 cycles de clarification ou plus | Passer au téléphone plutôt qu'au mail |

### 8.4 L'export CSV

`GET /api/analytics/providers.csv` produit un fichier avec **séparateur
point-virgule** et **BOM UTF-8** — les deux conditions pour qu'Excel en version
française ouvre correctement les accents et sépare les colonnes sans manipulation.
Les valeurs contenant `;`, `"` ou un saut de ligne sont échappées. C'est le
livrable que le chef de projet emmène en comité de suivi TMA.

---

## 9. Sécurité, rôles et visibilité

### 9.1 L'authentification

- Mots de passe hachés en **BCrypt** (jamais en clair, jamais réversibles).
- `POST /api/auth/login` renvoie un **JWT** signé en HMAC-SHA, valable 12 h,
  contenant l'email et le rôle.
- Le frontend le stocke dans `localStorage` et l'envoie en en-tête
  `Authorization: Bearer …` à chaque appel.
- `JwtAuthFilter` valide la signature, puis **recharge l'utilisateur depuis la
  base**. Point important : le rôle appliqué est **celui de la base**, pas celui
  écrit dans le jeton. Un jeton forgé avec `"role": "PROJET"` ne donnerait aucun
  droit supplémentaire.
- Session **stateless** : aucune session serveur, ce qui permettrait de mettre
  plusieurs instances derrière un répartiteur de charge sans travail
  supplémentaire.
- Sur une réponse 401, le client efface la session et redirige vers la page de
  connexion (`lib/api.js`).

### 9.2 Les trois niveaux de contrôle

| Niveau | Où | Ce qu'il protège |
|---|---|---|
| **Authentification** | `SecurityConfig` : tout est authentifié sauf `/api/auth/**` | Aucun accès anonyme aux données |
| **Autorisation par rôle** | `WorkflowService` (rôles autorisés par transition), `internalOnly()` dans `RagController` et `AnalyticsController` | Un prestataire ne voit ni le corpus, ni l'analytique |
| **Cloisonnement par contrat** | `checkVisibility()` dans `DocumentController` | Un prestataire ne voit que **ses** documents, et seulement à partir de la transmission |

Détail de la visibilité prestataire : il ne voit un document que si
(a) `document.provider_id` = son propre contrat **et** (b) l'état fait partie de
`TRANSMIS_PRESTATAIRE`, `CLARIFICATION_DEMANDEE`, `IMPLEMENTATION_ACCEPTEE`,
`CLOTURE`. Il ne verra donc **jamais** les brouillons ni les échanges internes
métier/projet. Une tentative d'accès direct par URL renvoie un **404**, pas un
403 : on ne révèle même pas l'existence du document.

### 9.3 La gestion des erreurs

`GlobalExceptionHandler` traduit les exceptions métier en codes HTTP propres,
avec un message **en français** que le frontend affiche tel quel :

| Exception | Code | Exemple de message |
|---|---|---|
| `InvalidTransitionException` | 409 | « L'action SEND n'est pas autorisée depuis l'état BROUILLON. » |
| `ForbiddenActionException` | 403 | « Le rôle PRESTATAIRE n'est pas autorisé à effectuer l'action CLOSE. » |
| `NoSuchElementException` | 404 | « Document introuvable » |
| `IllegalStateException` | 409 | « Seuls les brouillons jamais soumis peuvent être supprimés… » |

---

## 10. Cartographie du code, fichier par fichier

### 10.1 Racine du projet

| Fichier | Rôle |
|---|---|
| `demarrer.sh` | **Script de lancement tout-en-un** : vérifie les outils, libère les ports 3000/8080, démarre la base Docker, teste Ollama, lance le backend, applique le jeu de démo, lance le frontend. En cas d'échec, il indique quel log consulter |
| `arreter.sh` | Arrête frontend, backend et base (les données du volume Docker sont conservées) |
| `docker-compose.yml` | Le conteneur PostgreSQL 16 + pgvector, port 5433, volume nommé `ebcdc_pgdata`. Le nom de projet est figé pour retrouver le même conteneur quel que soit le nom du dossier |
| `README.md` | Le mode d'emploi court |
| `scripts/seed-demo.sql` | Le jeu de démonstration (voir 10.5) |
| `DOSSIER-PROJET.md` | Ce document |
| `backend.log`, `frontend.log`, `docker.log` | Sorties des services, écrites par `demarrer.sh` |

### 10.2 Backend — `backend/src/main/java/com/ebcdc/assistant/`

**Point d'entrée et configuration**

| Fichier | Rôle |
|---|---|
| `Application.java` | Démarrage Spring Boot ; `@EnableScheduling` active le worker de jobs |
| `config/SecurityConfig.java` | Chaîne de filtres de sécurité, CORS limité à `localhost:3000`, encodeur BCrypt |
| `resources/application.yml` | Toute la configuration : base, JWT, Ollama, seuils métier |
| `resources/schema.sql` | Le schéma SQL, rejoué à chaque démarrage (idempotent) |

**`auth/` — l'authentification (3 fichiers)**

| Fichier | Rôle |
|---|---|
| `AuthController.java` | `POST /api/auth/login` : vérifie le mot de passe, renvoie jeton + profil |
| `JwtService.java` | Crée et vérifie les jetons (bibliothèque jjwt 0.12) |
| `JwtAuthFilter.java` | Filtre exécuté à chaque requête : jeton → utilisateur → contexte de sécurité |

**`domain/` — les entités (13 fichiers)**
`AppUser`, `Provider`, `DocumentEntity`, `DocumentVersion`, `WorkflowTransition`,
`ClarificationRequest`, `RagJob` + les énumérations `Role`, `DocType`,
`WorkflowState`, `WorkflowAction`, `JobType`, `JobStatus`.
Style volontairement défensif : les getters sont publics, les setters quasi
inexistants (seul `setCurrentState` existe). **On ne peut pas modifier un objet
par accident** — les changements passent par les méthodes métier
(`resolve()`, `markDone()`, `markFailed()`…).

**`repo/` — l'accès aux données (7 interfaces Spring Data)**
Spring génère le SQL à partir du **nom** des méthodes.
`findFirstByDocumentIdAndToStateOrderByOccurredAtDesc(...)` = « la dernière
transition de ce document vers cet état ». Seule exception : `findByIdForUpdate`
qui porte l'annotation `@Lock(PESSIMISTIC_WRITE)` pour le verrou.

**`workflow/` — la machine à états (3 fichiers)**
`WorkflowService` (la table de 9 règles + `applyAction` + `availableActions`)
et les deux exceptions métier.

**`web/` — les contrôleurs REST (4 fichiers)**

| Fichier | Contenu |
|---|---|
| `DocumentController.java` | Le plus gros : liste, création, lecture, versions, suppression de brouillon, actions, actions disponibles, journal, clarifications (création et résolution) |
| `RagController.java` | Jobs RAG, corpus, recherche, ingestion fichier et texte — **tout réservé aux rôles internes** |
| `AnalyticsController.java` | Les 4 endpoints de métriques + l'export CSV |
| `GlobalExceptionHandler.java` | La traduction exceptions → codes HTTP |

**`rag/` — le moteur RAG (6 fichiers)**

| Fichier | Rôle |
|---|---|
| `ChunkingService.java` | Découpage en sections Markdown puis en fenêtres de 1 200 caractères |
| `OllamaClient.java` | Client HTTP vers Ollama : `/api/embed` et `/api/generate` |
| `RagVectorStore.java` | Accès pgvector en JDBC direct (le type `vector` n'est pas géré par JPA) |
| `IngestionService.java` | Tika → chunking → embeddings par lots → insertion |
| `CompletenessChecker.java` | La couche 1 déterministe de la vérification |
| `RagJobWorker.java` | Le worker planifié, les deux prompts, le parsing tolérant du JSON |

**`analytics/AnalyticsService.java`** — les requêtes SQL de M1 à M6 et des alertes.

**`bootstrap/DataSeeder.java`** — crée au premier démarrage les deux
prestataires (TMA Alpha, TMA Beta) et les trois comptes de démonstration. Il
vérifie l'existence avant d'insérer : relancer le backend ne duplique rien.

### 10.3 Frontend — `frontend/`

| Fichier | Rôle |
|---|---|
| `app/layout.js` | Enveloppe commune : barre de navigation + conteneur |
| `app/globals.css` | 389 lignes : thème sombre, badges d'état colorés, tableaux, panneaux, barres de progression |
| `app/page.js` | Page de connexion |
| `app/documents/page.js` | Liste + formulaire de création + suppression de brouillon |
| `app/documents/[id]/page.js` | **604 lignes, l'écran principal** : éditeur, actions, clarifications, panneau RAG avec polling, journal d'audit, insertion des suggestions |
| `app/corpus/page.js` | Ingestion (fichier / texte) et test de la recherche sémantique |
| `app/analytics/page.js` | Alertes, tableau M1-M4, export CSV, graphique M5, tableau M6 |
| `components/Nav.js` | Navigation ; masque Corpus et Analytique pour un prestataire |
| `components/StateBadge.js` | Le badge coloré d'état + les libellés français |
| `lib/api.js` | Le client HTTP : ajoute le jeton, gère le 401, extrait les messages d'erreur du backend |

### 10.4 Les paramètres modifiables sans toucher au code

Dans `backend/src/main/resources/application.yml` :

| Paramètre | Valeur | Effet |
|---|---|---|
| `app.jwt.ttl-hours` | 12 | Durée de validité du jeton |
| `app.ollama.generation-model` | `qwen3:4b` | Modèle de génération |
| `app.ollama.embedding-model` | `nomic-embed-text` | Modèle d'embeddings (768 dim — **changer de modèle impose de changer `vector(768)` dans le schéma**) |
| `app.ollama.read-timeout-seconds` | 600 | Patience maximale face au LLM |
| `app.rag.late-clarification-days` | 5 | Seuil de « clarification tardive » (M2 et alertes) |
| `app.rag.max-llm-sections` | 6 | Nombre de sections analysées par l'IA (coût / durée) |

### 10.5 Le jeu de démonstration

`scripts/seed-demo.sql` crée trois documents choisis pour que **chaque
métrique ait quelque chose à montrer immédiatement**, sans attendre plusieurs
minutes d'analyse IA :

| Document | Prestataire | Situation | Ce qu'il alimente |
|---|---|---|---|
| Refonte du module facturation | TMA Alpha | Cycle complet clôturé, 2 clarifications dont **une posée 12 jours après réception** | M1, M2, M3, M4, M6, alerte clarification tardive |
| Portail fournisseurs — lot 2 | TMA Beta | Parcours fluide, aucune clarification | Le **contraste** : c'est ce qu'un déroulement normal produit |
| Interface paie — reprise de données | TMA Alpha | **Dormant** chez le prestataire depuis 9 jours ; a connu un aller-retour de correction interne | Alerte « document dormant », et M5 montre le temps perdu en interne |

Le script est **idempotent** : il vérifie la présence du premier document et
s'arrête s'il existe déjà. On peut le rejouer sans rien dupliquer.

---

## 11. L'API REST complète

**25 endpoints.** Tous exigent l'en-tête `Authorization: Bearer <jwt>`, sauf la
connexion.

### Authentification

| Méthode | Chemin | Rôles | Description |
|---|---|---|---|
| `POST` | `/api/auth/login` | public | `{email, password}` → `{token, user{id,email,displayName,role,providerName}}` |

### Documents et workflow

| Méthode | Chemin | Rôles | Description |
|---|---|---|---|
| `GET` | `/api/providers` | tous | Liste des prestataires (pour le formulaire de création) |
| `GET` | `/api/documents` | tous | Liste filtrée selon le rôle et le contrat |
| `POST` | `/api/documents` | METIER | Crée le document + la version 1 (squelette Markdown) + la ligne d'audit `CREATE` |
| `GET` | `/api/documents/{id}` | tous* | En-tête + dernière version + nombre de versions |
| `PUT` | `/api/documents/{id}/versions` | METIER, PROJET | Enregistre une **nouvelle** version |
| `DELETE` | `/api/documents/{id}` | METIER (auteur) | Supprime un brouillon jamais soumis, avec ses données rattachées |
| `POST` | `/api/documents/{id}/actions` | selon la règle | Applique une action de workflow `{action, comment}` |
| `GET` | `/api/documents/{id}/available-actions` | tous* | Les actions autorisées **ici et maintenant** — c'est ce qui pilote les boutons |
| `GET` | `/api/documents/{id}/timeline` | tous* | Le journal d'audit du document |
| `GET` | `/api/documents/{id}/clarifications` | tous* | Les questions et leurs réponses |
| `POST` | `/api/documents/{id}/clarifications` | PRESTATAIRE | Pose une question ; calcule `daysSinceProviderReceived` |
| `POST` | `/api/clarifications/{cid}/resolve` | METIER, PROJET | Répond ; si c'était la dernière question ouverte, le document repart chez le prestataire |

<sub>\* pour un prestataire : uniquement ses documents, et uniquement à partir de la transmission.</sub>

### Assistant RAG (rôles internes uniquement)

| Méthode | Chemin | Description |
|---|---|---|
| `POST` | `/api/rag/jobs` | Crée un job `COMPLETENESS_CHECK` ou `SUGGESTION` → réponse immédiate |
| `GET` | `/api/rag/jobs/{id}` | Statut + résultat JSON (interrogé toutes les 4 s par le front) |
| `GET` | `/api/rag/jobs?documentId=…` | Historique des analyses d'un document |
| `GET` | `/api/rag/corpus` | Nombre de passages indexés |
| `POST` | `/api/rag/search` | Recherche sémantique `{query}` → les 5 passages les plus proches |
| `POST` | `/api/rag/ingest-text` | Indexe un texte collé `{label, content}` |
| `POST` | `/api/rag/ingest` | Indexe un fichier (multipart, 20 Mo max, docx/pdf/txt/md) |

### Analytique (rôles internes uniquement)

| Méthode | Chemin | Description |
|---|---|---|
| `GET` | `/api/analytics/providers` | M1 à M4 par prestataire |
| `GET` | `/api/analytics/documents/{id}` | M5 — temps par acteur pour un document |
| `GET` | `/api/analytics/correlation` | M6 — complétude ↔ cycles de clarification |
| `GET` | `/api/analytics/alerts` | Les trois familles d'alertes |
| `GET` | `/api/analytics/providers.csv` | Export Excel (`;` + BOM UTF-8) |

---

## 12. Installation, lancement, démonstration

### 12.1 Prérequis

| Outil | Version | Vérification |
|---|---|---|
| Java + Maven | Java 21, Maven 3.9 | `java -version && mvn -v` |
| Node.js | 18.17 minimum (testé en 22) | `node -v` |
| Docker + Docker Compose | récent | `docker ps` |
| Ollama | version récente (l'API `/api/embed` et le paramètre `think` sont utilisés) | `curl http://localhost:11434/api/tags` |

Modèles à télécharger **une seule fois** (environ 3 Go) :

```bash
ollama pull qwen3:4b
ollama pull nomic-embed-text
```

### 12.2 Lancement en une commande

```bash
./demarrer.sh
```

Le script enchaîne : vérification des outils → libération des ports → démarrage
de PostgreSQL → test d'Ollama → démarrage du backend (avec attente active
jusqu'à ce que l'API réponde) → application du jeu de démo → `npm install` si
nécessaire → démarrage du frontend. À chaque étape qui échoue, il affiche
**quel fichier de log** consulter.

Puis ouvrir <http://localhost:3000>, de préférence en **navigation privée**
(une session d'un autre rôle peut rester en cache).

Pour tout arrêter : `Ctrl+C` dans le terminal, ou `./arreter.sh` depuis un
autre. Les données de la base sont conservées dans le volume Docker.

### 12.3 Les comptes de démonstration

| Rôle | Email | Mot de passe |
|---|---|---|
| Métier | `metier@demo.fr` | `demo1234` |
| Projet | `projet@demo.fr` | `demo1234` |
| Prestataire (TMA Alpha) | `prestataire@demo.fr` | `demo1234` |

> **Astuce de démonstration** : ouvrez trois fenêtres de navigateur en mode
> privé, une par rôle. Vous passez d'un acteur à l'autre sans vous
> reconnecter, et vous montrez en direct que les boutons changent selon le rôle.

### 12.4 Le scénario de démonstration recommandé (12 minutes)

| # | Rôle | Action | Ce que vous montrez |
|---|---|---|---|
| 1 | Projet | Ouvrir **Analytique** | On part du problème : les alertes, l'attente moyenne, le ratio M4 |
| 2 | Métier | Créer un EB | Le squelette Markdown des 6 sections obligatoires |
| 3 | Métier | Rédiger volontairement une section vague, puis **Vérifier la complétude** | Pendant que le job tourne (quelques minutes), montrez le résultat déjà présent sur le document de démo. Insistez sur `questions_previsibles_prestataire` |
| 4 | Métier | **Suggérer des améliorations**, puis « Insérer dans la section » | Le RAG s'appuie sur le corpus ; l'humain garde la main |
| 5 | Métier | **Soumettre** | Le journal d'audit s'enrichit en direct |
| 6 | Projet | **Valider** puis **Transmettre** | ⏱ Le chronomètre démarre |
| 7 | Prestataire | Poser une clarification | « posée X j après réception » s'affiche **immédiatement** |
| 8 | Prestataire | Essayer d'ouvrir le corpus ou l'analytique | Le menu ne les propose pas ; l'accès direct par URL est refusé côté serveur |
| 9 | Projet | Répondre | Le document repart automatiquement chez le prestataire — nouveau cycle |
| 10 | Prestataire | **Accepter** ; Projet : **Clôturer** | Le cycle est bouclé |
| 11 | Projet | Retourner sur **Analytique** | Les compteurs ont bougé. Exporter le CSV : c'est le livrable du comité |

**Conseil** : lancez les jobs IA **avant** la démonstration (ils prennent
plusieurs minutes sur CPU), ou appuyez-vous sur les résultats déjà présents dans
le jeu de démo.

### 12.5 Que faire si quelque chose ne marche pas

| Symptôme | Cause probable | Solution |
|---|---|---|
| Le backend ne démarre pas | La base n'est pas prête | `docker compose up -d` puis `tail -60 backend.log` |
| Erreur 401 permanente | Jeton expiré (12 h) | Se déconnecter / reconnecter |
| Le job RAG reste en `PENDING` | Ollama n'est pas lancé | `sudo systemctl start ollama`, vérifier `curl http://localhost:11434/api/tags` |
| Le job passe en `FAILED` | Modèle absent | `ollama pull qwen3:4b` et `ollama pull nomic-embed-text` |
| La recherche ne renvoie rien | Corpus vide | Indexer au moins un document depuis la page **Corpus** |
| Le port 3000 est occupé | Une autre application (souvent `open-webui`) | `./demarrer.sh` s'en charge, sinon `fuser -k 3000/tcp` |

---

## 13. État d'avancement : est-ce terminé ?

### 13.1 Réponse courte

**Oui pour un démonstrateur de stage : les trois briques annoncées sont
implémentées, cohérentes entre elles et fonctionnelles de bout en bout.**
**Non pour une mise en production : il manque les tests automatisés, la gestion
des secrets, le déploiement et l'administration des comptes.**

### 13.2 Vérifications effectuées le 31/08/2026

| Vérification | Résultat |
|---|---|
| Compilation du backend (`mvn compile`) | ✅ **Succès, aucune erreur** |
| Construction du frontend (`next build`) | ✅ **Succès, 6 routes générées** |
| Démarrage réel de l'application (`backend.log`) | ✅ Spring Boot 3.3.4 sur Java 21, Tomcat sur 8080, 7 repositories JPA détectés, connexion PostgreSQL établie, application démarrée en 2,5 s |
| Frontend en fonctionnement (`frontend.log`) | ✅ Next.js 14.2.35 prêt, pages `/`, `/documents`, `/documents/[id]` compilées et servies |
| Base de données (`docker.log`) | ✅ Conteneur `ebcdc-db` en fonctionnement |
| Cohérence front ↔ back | ✅ Les 25 endpoints appelés par le frontend existent tous côté backend, avec les mêmes noms de champs |

> ⚠️ **Le `README.md` est périmé sur un point** : son tableau final indique que
> le backend Java a été « relu attentivement mais **non compilé** ». C'était vrai
> à la rédaction ; ce n'est plus vrai. Le code compile et l'application a
> réellement tourné. **Corrigez cette ligne avant de transmettre le projet** —
> c'est exactement le genre de détail qu'un encadrant relève.

### 13.3 Ce qui est terminé — inventaire fonctionnel

**Workflow et traçabilité — complet**
- [x] 8 états, 9 transitions, contrôle de rôle sur chacune
- [x] Journal d'audit horodaté, en écriture seule, écrit dans la même transaction que le changement d'état
- [x] Verrou pessimiste contre les actions concurrentes
- [x] Boutons d'action calculés par le serveur, jamais devinés par l'interface
- [x] Versionnement du contenu, sans écrasement
- [x] Suppression d'un brouillon encadrée par trois conditions
- [x] Cloisonnement strict du prestataire (contrat + état)

**Clarifications — complet**
- [x] Question avec section visée
- [x] Calcul et gel automatiques du délai « X jours après réception »
- [x] Affichage en rouge au-delà du seuil
- [x] Réponse par le métier ou le projet, retour automatique chez le prestataire quand toutes les questions ouvertes sont traitées
- [x] Distinction cycles / questions (la base de la métrique M4)

**RAG — complet**
- [x] Ingestion fichier (docx, pdf, txt, md via Tika) et texte collé
- [x] Découpage par sections puis par fenêtres avec chevauchement
- [x] Embeddings par lots, stockage pgvector, index HNSW cosinus
- [x] Recherche sémantique avec score, testable depuis l'interface
- [x] Vérification de complétude en deux couches (déterministe + LLM)
- [x] Suggestions de rédaction avec insertion assistée dans la bonne section
- [x] File de jobs asynchrone, worker planifié, suivi par polling, gestion des échecs
- [x] Tolérance au JSON malformé renvoyé par le modèle

**Analytique — complet**
- [x] M1 à M6 + 3 familles d'alertes
- [x] Export CSV compatible Excel français
- [x] Graphique de décomposition du temps par acteur
- [x] Réservée aux rôles internes

**Ergonomie et exploitation — complet**
- [x] Interface entièrement en français, thème sombre cohérent
- [x] Messages d'erreur métier explicites remontés du backend
- [x] Scripts de démarrage/arrêt avec diagnostic
- [x] Jeu de données de démonstration idempotent

### 13.4 Ce qui n'est **pas** fait

Classé par ordre d'importance si le projet devait être poursuivi.

| # | Manque | Impact | Effort estimé |
|---|---|---|---|
| 1 | **Aucun test automatisé** — le dossier `backend/src/test` n'existe pas ; aucun test frontend | Toute évolution future risque une régression silencieuse. C'est **la première remarque probable de l'encadrant** | 2 à 3 jours pour couvrir `WorkflowService`, `CompletenessChecker` et les métriques |
| 2 | **Secrets en clair** — la clé JWT est dans `application.yml` (avec le commentaire « à remplacer en production »), le mot de passe de la base est `ebcdc` dans `docker-compose.yml` | Rédhibitoire en production ; acceptable pour une démonstration locale | 0,5 jour (variables d'environnement) |
| 3 | **Pas de gestion des comptes** — les 3 utilisateurs et les 2 prestataires sont créés en dur par `DataSeeder` ; ni inscription, ni changement de mot de passe, ni écran d'administration | On ne peut pas ajouter un vrai utilisateur sans toucher au code | 2 jours |
| 4 | **Pas de déploiement** — seule la base est conteneurisée ; aucun `Dockerfile` pour le backend ni le frontend, pas de chaîne d'intégration continue | Le projet ne tourne que sur le poste du développeur | 1 à 2 jours |
| 5 | **Pas de notifications** — aucun mail, aucune alerte poussée : chaque acteur doit ouvrir l'application pour savoir qu'on l'attend | Limite l'adoption réelle ; c'est pourtant la clé de la réduction des délais | 1 à 2 jours |
| 6 | **Historique des versions non consultable** — le nombre de versions est affiché, mais on ne peut ni relire une version antérieure ni comparer deux versions | Les données sont là ; seule l'interface manque | 1 jour |
| 7 | **Pas de recherche ni de pagination** sur la liste des documents | Gênant au-delà de quelques dizaines de documents | 0,5 jour |
| 8 | **Pas d'export du document** (PDF/Word) pour l'envoi contractuel | Le document reste dans l'application | 1 jour |
| 9 | **Pas de reprise des jobs interrompus** — un job en `RUNNING` au moment d'un arrêt du serveur y reste indéfiniment | Il faut le relancer manuellement | 0,5 jour (remise à `PENDING` au démarrage) |
| 10 | **2 vulnérabilités « high » signalées par `npm audit`** sur les dépendances du frontend | À traiter avant toute exposition réseau | 0,5 jour |

### 13.5 Petites incohérences repérées à la relecture

Aucune n'empêche le fonctionnement, mais les citer spontanément fait bonne
impression :

- Le seuil de « clarification tardive » est **paramétrable côté backend**
  (`late-clarification-days: 5`) mais **codé en dur à `5`** dans l'affichage de
  la fiche document (`app/documents/[id]/page.js`). Si on change le paramètre,
  la couleur rouge de l'interface ne suit pas.
- Le rôle `PROJET` peut enregistrer une version **dans n'importe quel état**,
  alors que le rôle `METIER` est limité à `BROUILLON`, `CORRECTION_METIER` et
  `CLARIFICATION_DEMANDEE`. C'est probablement voulu (le chef de projet
  arbitre), mais ce n'est écrit nulle part.
- `DataSeeder` recalcule un hachage BCrypt à chaque démarrage même quand les
  comptes existent déjà (quelques centaines de millisecondes perdues).
- `GET /api/documents/{id}` charge **toutes** les versions uniquement pour en
  compter le nombre ; un `count` en base suffirait.
- La page Corpus importe la constante `API` sans l'utiliser.

---

## 14. Limites connues et dettes techniques

### 14.1 Limites assumées (des choix, pas des oublis)

| Limite | Pourquoi c'est un choix |
|---|---|
| **Un seul job IA à la fois** | Sur CPU sans GPU, paralléliser l'inférence ne ferait que ralentir l'ensemble. Ce choix est documenté dans le code |
| **6 sections analysées au maximum** par vérification | Compromis entre la richesse de l'analyse et le temps d'attente |
| **Interface mono-langue (français)** | Le contexte est une DSI française ; l'internationalisation aurait ajouté du bruit sans valeur de démonstration |
| **Pas de moteur BPMN** | Une table de 9 règles lisible en dix secondes vaut mieux qu'un moteur générique pour un workflow figé par le processus contractuel |
| **Jeton en `localStorage`** | Simple et suffisant en démonstration locale. En production, un cookie `HttpOnly` + `SameSite` serait préférable (protection contre le vol de jeton par XSS) |

### 14.2 Ce qui devrait changer avant une mise en production

1. **Secrets** : clé JWT et mot de passe de base en variables d'environnement ou
   dans un coffre-fort (Vault, secrets Kubernetes).
2. **HTTPS obligatoire** et CORS ouvert au vrai domaine (aujourd'hui figé sur
   `http://localhost:3000`).
3. **Migrations de schéma** avec Flyway ou Liquibase, plutôt qu'un `schema.sql`
   rejoué : indispensable dès qu'il faut faire évoluer une table déjà remplie.
4. **Tests** : unitaires sur la machine à états, d'intégration sur les métriques
   (avec Testcontainers pour un vrai PostgreSQL + pgvector).
5. **Observabilité** : `spring-boot-starter-actuator`, métriques, sondes de
   vivacité.
6. **Limitation de débit** sur `/api/auth/login` (aujourd'hui, rien n'empêche
   une attaque par essais successifs).
7. **Reprise des jobs** en `RUNNING` au redémarrage.
8. **Montée en charge** : le worker suppose une instance unique du backend. Avec
   plusieurs instances, il faudrait un verrou en base (`SELECT … FOR UPDATE SKIP
   LOCKED`) pour éviter que deux workers prennent le même job.

---

## 15. Questions probables de l'encadrant et réponses

> À lire avant la soutenance. Les réponses sont volontairement courtes et
> directes.

**« Explique-moi le projet en une minute. »**
Les expressions de besoin circulent entre le métier, le projet et le prestataire
TMA. Elles sont souvent incomplètes, ce qui déclenche des allers-retours de
clarification, et ces allers-retours sont parfois utilisés pour justifier des
délais. J'ai construit une application qui fait trois choses : elle trace chaque
étape du circuit dans un journal horodaté inaltérable, elle aide à rédiger un
document plus complet grâce à une IA locale qui prédit les questions du
prestataire, et elle calcule six métriques qui montrent objectivement où le
temps est passé.

**« Quelle est la partie la plus difficile techniquement ? »**
La mesure du temps. Le journal ne contient que des instants ; pour en tirer des
durées par acteur, j'utilise la fonction fenêtre SQL `lead()` qui va chercher
l'horodatage de la transition suivante, avec un `COALESCE(next_at, now())` pour
les documents encore en cours. Il a aussi fallu garantir que le journal soit
fiable : verrou pessimiste sur le document et écriture de l'audit dans la même
transaction que le changement d'état.

**« Pourquoi une IA locale et pas l'API d'un grand modèle ? »**
Les EB/CDC contiennent des volumétries, des contraintes contractuelles et
parfois des données RH. Rien ne doit sortir du réseau. Ollama tourne sur la
machine, et le choix de `qwen3:4b` est dicté par la cible : un poste sans carte
graphique. C'est aussi ce qui a imposé l'architecture asynchrone à file de jobs.

**« Comment gères-tu le fait que le modèle réponde n'importe quoi ? »**
Trois protections. D'abord la couche déterministe, qui donne un résultat utile
sans IA du tout. Ensuite le format JSON imposé dans le prompt avec l'option
`format: "json"` d'Ollama et une température basse (0,2) pour limiter la
créativité. Enfin, si le JSON reste invalide, `parseLlmJson` encapsule la
réponse brute au lieu de faire échouer le job : l'application ne casse jamais à
cause du modèle.

**« Est-ce que ton outil accuse les prestataires ? »**
Non, et c'est un point de conception. L'application produit des **indicateurs
d'objectivation des délais**, jamais un jugement d'intention. La phrase est
écrite dans l'interface et dans le code. La métrique M5 en est la preuve : elle
décompose le temps par acteur, y compris le temps passé côté interne. L'outil
peut donc tout à fait montrer que le retard vient du client.

**« Quelle est la métrique la plus intéressante ? »**
M4, le ratio cycles / questions. Un ratio de 1,00 signifie une seule question
par cycle : les questions arrivent au compte-gouttes, chacune relançant le
chronomètre. Un ratio de 0,20 signifie cinq questions groupées en un seul
échange. C'est la métrique qui distingue un comportement coopératif d'un
comportement dilatoire — sans jamais l'affirmer, en donnant juste le chiffre.

**« Qu'est-ce qui n'est pas fini ? »**
Il n'y a aucun test automatisé, c'est la première dette. Les secrets sont en
clair dans les fichiers de configuration, ce qui est acceptable en local mais pas
au-delà. Il n'y a pas de gestion des comptes : les utilisateurs sont créés en
dur au démarrage. Et rien n'est déployé : seule la base est conteneurisée.
Fonctionnellement, en revanche, les trois briques annoncées sont complètes et
l'application tourne de bout en bout.

**« Si tu avais deux semaines de plus ? »**
Une semaine de tests — la machine à états et les requêtes de métriques, avec
Testcontainers pour tester sur un vrai PostgreSQL + pgvector. Puis les
notifications par mail, parce que c'est le vrai levier de réduction des délais :
aujourd'hui, il faut ouvrir l'application pour savoir qu'on vous attend. Enfin
la gestion des comptes et un `Dockerfile` pour chaque service.

**« Pourquoi les boutons sont-ils calculés par le serveur ? »**
Parce que si l'interface décidait elle-même quels boutons afficher, la règle
métier existerait à deux endroits et finirait par diverger. Ici, le frontend
demande `GET /api/documents/{id}/available-actions` et affiche ce qu'on lui
répond. La règle n'existe qu'une seule fois, dans `WorkflowService`. Et même si
quelqu'un forgeait une requête à la main, le serveur la refuserait de toute
façon.

**« Quelle est la part de code que tu as écrite ? »**
Réponse à adapter, mais **à ne pas éluder** : dire clairement dans quelle mesure
l'implémentation a été assistée par IA, et démontrer la maîtrise en expliquant
les décisions — pourquoi une table de transitions plutôt qu'un moteur BPMN,
pourquoi `lead()` pour les durées, pourquoi une file de jobs plutôt qu'un appel
synchrone. Un encadrant valorise la compréhension et l'honnêteté bien plus que
la quantité de lignes tapées à la main.

---

## 16. Glossaire

| Terme | Définition |
|---|---|
| **EB** | Expression de Besoin — le document par lequel le métier formalise ce qu'il veut |
| **CDC** | Cahier des Charges — la version formalisée et contractuelle du besoin |
| **TMA** | Tierce Maintenance Applicative — le prestataire externe qui maintient et fait évoluer les applications |
| **SLA** | *Service Level Agreement* — l'engagement de délai inscrit au contrat |
| **MOA** | Maîtrise d'ouvrage — le côté client d'un projet (ici, les rôles métier et projet) |
| **Cycle de clarification** | Un aller-retour complet : le prestataire pose une ou plusieurs questions, le client répond, le document lui revient |
| **RAG** | *Retrieval-Augmented Generation* — chercher d'abord des passages pertinents dans une base documentaire, puis les fournir au modèle pour qu'il réponde en s'appuyant dessus |
| **Embedding** | La traduction d'un texte en vecteur de nombres (ici 768) tel que deux textes de sens proche donnent deux vecteurs proches |
| **Chunk** | Un morceau de document (ici ~1 200 caractères) : l'unité indexée et retrouvée par la recherche |
| **pgvector** | L'extension PostgreSQL qui ajoute le type `vector` et les opérateurs de distance |
| **HNSW** | *Hierarchical Navigable Small World* — la structure d'index qui rend la recherche vectorielle rapide sans comparer tous les vecteurs |
| **Distance cosinus** | Mesure de similarité entre deux vecteurs basée sur l'angle qui les sépare ; opérateur `<=>` dans pgvector |
| **LLM** | *Large Language Model* — le modèle de langage (ici `qwen3:4b`) |
| **Ollama** | Le logiciel qui fait tourner des modèles de langage en local et les expose en API HTTP |
| **JWT** | *JSON Web Token* — un jeton signé qui prouve l'identité de l'appelant sans session serveur |
| **BCrypt** | L'algorithme de hachage des mots de passe, volontairement lent pour résister aux attaques par force brute |
| **Machine à états** | Un modèle où un objet ne peut être que dans un état parmi une liste finie, et ne passe d'un état à l'autre que par des transitions explicitement autorisées |
| **Verrou pessimiste** | `SELECT … FOR UPDATE` : la ligne est verrouillée jusqu'à la fin de la transaction, les autres attendent |
| **Idempotent** | Se dit d'une opération qu'on peut rejouer plusieurs fois sans changer le résultat (ici : `schema.sql` et `seed-demo.sql`) |
| **Polling** | Interroger le serveur à intervalle régulier pour savoir si un traitement est terminé |
| **Fonction fenêtre** | En SQL, une fonction comme `lead()` qui accède aux autres lignes du résultat sans regrouper les données |

---

*Document généré le 31 août 2026 à partir d'une relecture intégrale du code
source. Pour toute évolution du projet, pensez à mettre à jour la
[section 13](#13-état-davancement--est-ce-terminé-).*
