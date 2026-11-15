# Mini Launcher

Launcher Android minimaliste : heure, date, favoris en texte, recherche (balayage vers le haut)
et menu radial en coin bas-droit piloté comme un joystick.

## Geste du menu radial
- Poser le pouce sur la poignée (coin bas-droit).
- Pousser vers l'extérieur : les groupes apparaissent ; pousser plus loin ouvre le groupe visé.
- Glisser le long de l'arc : choisir l'appli (au bout de l'arc, la liste défile sans fin).
- Relâcher sur une appli : elle se lance. Relâcher au centre : annule.
- Pousser vers l'intérieur ou vers la flèche du coin : referme le groupe.
- Petit coup de pouce vers l'extérieur puis relâcher : rouvre la dernière appli lancée.

## Personnalisation
Appui long sur l'heure ou la date : bouton « Changer d'appli d'accueil », groupes (ajout, renommage, suppression, 5 max),
groupe de chaque appli, favoris (étoile), sons et vibrations.

## Obtenir l'APK sans rien installer sur le PC
1. Créer un dépôt GitHub (privé ou public) et y envoyer tout le contenu de ce dossier.
2. Onglet **Actions** > **Build APK** > le workflow démarre seul (ou « Run workflow »).
3. À la fin, ouvrir l'exécution et télécharger l'artefact **MiniLauncher-debug-apk**.
4. Copier `app-debug.apk` sur le téléphone, l'ouvrir et autoriser l'installation depuis cette source.
5. Réglages Android > Applis par défaut > Appli d'accueil > **Mini Launcher**.

## Notes
- Aucune dépendance externe (pas d'AndroidX) : build court et peu fragile.
- L'APK est signé avec la clé de debug : suffisant pour un usage personnel.
- Sons : `app/src/main/res/raw/` (remplaçables par d'autres .wav du même nom).
