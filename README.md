# Xnative

Application Android native de lecture et de téléchargement des médias X/Twitter via **FxTwitter / FxEmbed**, en français, développée en Kotlin avec Jetpack Compose et Material 3.

**Version 1.2.0 · Android 7.0 minimum (API 24) · cible Android 15 (API 35)**

Identifiant conservé : **`com.jfdedit3.xnative.reader`**. Aucun compte X ni clé API n’est nécessaire. Ce dépôt concerne uniquement Android ; la version Windows du document de référence ne fait pas partie de cette livraison.

## Navigation

| Onglet | Fonctions |
| --- | --- |
| Favoris | Comptes suivis localement, fiches persistantes avec compteur de médias, posts enregistrés, rafraîchissement des fiches par geste. |
| Actus | Fil chronologique fusionné, rafraîchissement, chargement automatique des pages précédentes au bas du fil et bouton de pagination. |
| Galerie | Médias téléchargés regroupés par compte, taille réellement relue sur le disque, vignettes, progression du lot et liste complète des échecs avec leurs motifs. Accès aux réglages par l’engrenage. |
| Recherche | Recherche de comptes et de publications sur FxTwitter, pagination des résultats, ouverture de liens ou d’un pseudo. |
| Profil | Bannière, avatar, bio, localisation, site, date d’inscription, compteurs et badges, suivi local, Publications / Images / Vidéos, téléchargement en lot et lien RSS. |

Touchez le nom, le pseudo ou l’avatar d’un auteur pour ouvrir son profil. Les comptes des Favoris sont également cliquables. Un pseudo ou un lien de profil peut être saisi sans suivre le compte. Les liens X, Twitter, FxTwitter et FixupX sont reconnus, y compris via **Partager → Xnative** ; les pages réservées telles que `home`, `explore` ou `notifications` ne sont pas interprétées comme des profils.

Les médias d’un compte sont paginés automatiquement à l’ouverture d’**Images** ou **Vidéos**, jusqu’à l’épuisement du curseur fourni par FxTwitter. Un bouton permet aussi de charger tous les médias depuis Publications et d’arrêter le chargement. Un curseur répété provoque une erreur explicite plutôt qu’une boucle infinie. Le lot concerne les médias déjà chargés : attendre la fin du chargement pour récupérer la totalité accessible via le service.

## Lecture et téléchargements

- Visionneuse plein écran : balayage, boutons précédent/suivant, zoom par pincement et double toucher.
- Lecteur vidéo Media3 intégré, avec contrôles, lecture en boucle et pause lorsque l’application passe en arrière-plan.
- Téléchargement individuel ou en lot ; filtre Tout / Images / Vidéos ; confirmation désactivable.
- Un seul lot à la fois, exécuté par un service de premier plan Android.
- Notification de progression : fichier courant, octets transférés, vitesse et action **Annuler** ; récapitulatif de fin de lot.
- L’annulation coupe l’appel HTTP actif. Les fichiers terminés sont conservés et le fichier incomplet est supprimé.
- Historique persistant avec reprise manuelle des échecs depuis Galerie. Une réouverture de l’application nettoie les fichiers interrompus par la mort du processus, sans supprimer un transfert encore actif.
- Noms déterministes fondés sur la publication et l’identité de l’URL du média. L’ordre d’un lot ne modifie pas les noms ; les variations de qualité des URL Twitter n’en créent pas de nouveaux. Un fichier différent déjà présent n’est jamais écrasé.
- Formats : JPEG, PNG, GIF, WebP, MP4 et WebM. Les GIF natifs restent `.gif` et sont classés comme images. Les animations transmises par X en MP4 sont conservées en MP4 ; aucune fausse extension GIF n’est appliquée et il n’y a pas de conversion vidéo vers GIF.

Les tailles de la Galerie sont obtenues à partir des URI des fichiers et non d’une estimation du transfert. Un fichier supprimé en dehors de l’application n’est plus affiché. La Galerie lit uniquement les téléchargements connus de Xnative et ne scanne pas toutes les photos du téléphone.

## Stockage et permissions

Destination par défaut : **`Download/XNative/<pseudo>/`**. Un dossier parent personnalisé peut être choisi avec le sélecteur Android ; Xnative y crée `XNative/<pseudo>/`. Le droit d’accès à ce dossier est conservé après redémarrage.

Sur Android 10 et ultérieur, les fichiers passent par MediaStore avec `IS_PENDING` : publication après transfert complet, suppression en cas d’échec. Sur Android 7 à 9, un fichier temporaire est écrit puis renommé sans écrasement ; l’autorisation d’écriture est demandée au moment de l’opération. Un dossier personnalisé utilise le Storage Access Framework et un fichier temporaire renommé après transfert.

L’autorisation de notifications est demandée sur Android 13 et ultérieur lors de l’activation du RSS ou d’un téléchargement. Si elle est refusée, la progression reste consultable dans Galerie ; Android peut afficher le service uniquement dans son gestionnaire de tâches. Aucune autorisation globale de lecture de toutes les photos/vidéos n’est nécessaire pour les fichiers créés par l’application.

Les abonnements, posts enregistrés et fils sont dans `library.json`, les fiches dans `profiles.json`, l’historique dans `downloads.json` et son journal d’événements `downloads.events`, dans l’espace privé de l’application. Les snapshots sont écrits atomiquement. L’historique ajoute des événements synchronisés sur disque, compacte son journal et ignore une dernière ligne interrompue. Les images ont aussi le cache de Coil, sans garantie de disponibilité hors connexion après éviction de ce cache.

## Réglages, export et diagnostic

Les réglages sont accessibles depuis l’engrenage de Galerie :

- animations de la visionneuse et transitions d’images ; durée des vidéos ; confirmation des lots ; filtre de contenu et dossier ; taille des pages de 10 à 100 posts ; trois essais réseau activables et détails des erreurs ;
- rafraîchissement et vidage du cache des fiches, sans retirer les abonnements ni les posts enregistrés ;
- comptes surveillés par RSS, économie de batterie, analyse immédiate et accès aux paramètres batterie Android ;
- notification au démarrage, journal consultable et exportable, relance du fil et accès aux téléchargements.

**Exporter les favoris** écrit `Download/XNative/favoris_export.json` (ou le dossier choisi). Le document UTF-8 est versionné : `format: "XNative.favorites"`, `version: 1`, puis `accounts`, `posts` et `profiles`. Les données utilisent des liens publics et pas des URI de stockage Android. Un consommateur Windows peut implémenter ce schéma ; aucun exécutable Windows ni importeur Windows existant n’a été fourni ou testé dans ce projet.

Le journal de débogage est privé et limité en taille. Un journal de démarrage est publié sous `Download/XNative/dernier_lancement.log` lorsque l’accès au stockage le permet ; un export complet est disponible dans les réglages. Les exports précédemment créés par l’application peuvent être remplacés ; les médias téléchargés ne sont jamais écrasés.

## Surveillance RSS

Sélectionnez les comptes dans Réglages, puis activez la surveillance. L’activation programme une première analyse et une tâche persistante WorkManager. La première analyse crée la référence des publications connues ; elle ne notifie pas l’intégralité des archives. Les analyses suivantes notifient les nouvelles publications présentes dans le flux.

Android planifie les vérifications environ toutes les **15 minutes**, ou **1 heure** en économie de batterie. Les contraintes système, Doze, la batterie et le réseau peuvent les différer : ce n’est pas un service push et les publications qui ont déjà disparu du flux ne peuvent pas être récupérées rétroactivement. WorkManager reprogramme les analyses après un redémarrage. Un téléchargement interrompu n’est pas redémarré automatiquement au démarrage du téléphone ; les restrictions Android sur les services de premier plan sont respectées.

**Flux RSS ↗** sur un profil ouvre son URL de flux dans un navigateur ou un lecteur RSS associé. Le réglage « Publications via RSS uniquement » concerne les fils ; la recherche, les informations de profil et les archives complètes de médias utilisent l’API.

## Compiler et installer

Prérequis : Android Studio récent, JDK 17 ou 21, SDK Android 35 et Build Tools 35.0.0. Le wrapper Gradle est inclus. Définir `ANDROID_HOME` ou créer un `local.properties` non versionné avec `sdk.dir=/chemin/android-sdk`.

```sh
./gradlew testDebugUnitTest lintDebug assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Le workflow GitHub Actions **Android** exécute les tests et Lint puis produit l’artefact **Xnative-debug**. Les tests Robolectric téléchargent leurs environnements Android depuis Maven Central lors du premier passage. Les APK de développement sont signés avec une clé de test ; une distribution et des mises à jour durables nécessitent une clé de signature conservée par le propriétaire.

## Sources et limites

- `https://api.fxtwitter.com/2/profile/{handle}`
- `https://api.fxtwitter.com/2/profile/{handle}/statuses?count=...&cursor=...`
- `https://api.fxtwitter.com/2/profile/{handle}/media?count=...&cursor=...`
- `https://api.fxtwitter.com/2/search?q=...&cursor=...`
- `https://api.fxtwitter.com/2/typeahead?q=...&result_type=users`
- `https://api.fxtwitter.com/status/{id}`
- `https://fxtwitter.com/{handle}/feed.xml`

Références : [API FxEmbed](https://docs.fxembed.com/api/introduction), [flux RSS et Atom](https://docs.fxembed.com/guide/advanced/rss-atom-feeds/).

Lors des vérifications du 18 octobre 2026, la recherche de comptes répondait, mais les requêtes de recherche de posts essayées renvoyaient HTTP 404. L’application signale cet échec et propose alors les résultats de son cache local ; elle ne présente pas ces résultats comme une recherche distante réussie.

La disponibilité, les curseurs, les résultats de recherche et l’exhaustivité des archives dépendent de FxTwitter et de X. Les comptes privés et les médias supprimés peuvent être indisponibles. L’application n’authentifie pas un compte X : publier, aimer, répondre et republier ouvrent X ; le suivi et les favoris restent locaux.

Les tests couvrent notamment les formats API/RSS, les curseurs, les noms stables, l’export, le stockage MediaStore, le stockage ancien Android et l’annulation d’un flux simulé. La lecture vidéo, les gestes et le comportement des notifications/économie de batterie doivent également être validés sur des téléphones réels ; Robolectric ne remplace pas cette validation matérielle.
