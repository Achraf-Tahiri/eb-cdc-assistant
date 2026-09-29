# Ma fiche de test

Date :
Navigateur :
Nom de mon document principal :
Adresse du document principal :

Pour chaque ligne : noter OK, ÉCHEC ou NON TESTÉ. Une fonctionnalité non testée n'est pas validée.

| Test | Résultat | Observation |
|---|---|---|
| Mot de passe incorrect : erreur visible | | |
| Connexion correcte : nom et rôle attendus | | |
| Création EB : brouillon et modèle de sections | | |
| Enregistrement : nouvelle version et contenu conservé après actualisation | | |
| Modifications non enregistrées : soumission bloquée | | |
| Checklist : sections vides distinguées des sections absentes | | |
| Complétude IA : résultat lisible ; remarques examinées | | |
| Corpus : texte indexé et retrouvé par la recherche | | |
| Suggestions : texte lisible, insertion dans la bonne section et sauvegarde | | |
| Prestataire : brouillon invisible, y compris via son adresse | | |
| Correction demandée par le projet puis nouvelle soumission métier | | |
| Validation puis transmission au prestataire | | |
| Deux questions dans un cycle : retour seulement après les deux réponses | | |
| Deux cycles supplémentaires : 3 cycles et 4 questions au total | | |
| Acceptation puis clôture | | |
| Historique : ordre des actions, commentaires et dates | | |
| Analytique M6 : 3 cycles et 4 questions pour le document testé | | |
| Alerte : cycles répétés pour ce document | | |
| M5 : décomposition affichée (0 jour peut être normal sur un essai rapide) | | |
| Export CSV : fichier téléchargé et contenu lisible | | |
| Prestataire : Corpus et Analytique inaccessibles | | |
| Contrat Beta : invisible au compte Alpha | | |
| Brouillon de test : annulation de suppression puis suppression confirmée | | |
| Document soumis : suppression indisponible | | |
| Document sans prestataire : transmission refusée | | |
| Type CDC : parcours complet vérifié séparément | | |
| Import TXT | | |
| Import MD | | |
| Import DOCX créé dans Word/LibreOffice | | |
| Import PDF texte exporté depuis Word/LibreOffice | | |
| Déconnexion : accès privé redirigé vers la connexion | | |
| Actualisation : document, réponses et état conservés | | |
| Arrêt/redémarrage : données conservées | | |

## Décrire une anomalie

Numéro du test :
Compte utilisé :
Document et état avant l'action :
Action effectuée :
Résultat attendu :
Résultat obtenu :
Message d'erreur exact :
Capture éventuelle :

## Évaluer une réponse IA

Pour chaque remarque : utile / déjà précisé / incorrect / hors sujet.
Pour chaque suggestion : utile / source vérifiable / information ajoutée sans source.
Le statut DONE signifie que le traitement est terminé, pas que la réponse est correcte.

## Limites connues à ne pas confondre avec une nouvelle panne

- Le rôle projet peut encore éditer après clôture : règle à faire valider.
- La complétude peut créer une version automatiquement ; les numéros peuvent donc sauter entre deux enregistrements manuels.
- Le corpus conserve les doublons si un même texte est importé plusieurs fois.
- Le modèle peut produire des remarques inexactes, une source imprécise ou un titre vide.
- L'historique des anciennes versions n'a pas encore d'écran de consultation.
- Un job interrompu pendant son exécution peut rester RUNNING après un redémarrage.
- Un document sans prestataire ne peut pas être réaffecté depuis l'interface actuelle.
- Les modèles scannés, gros fichiers, tests de charge et sécurité exhaustive demandent des tests supplémentaires.
