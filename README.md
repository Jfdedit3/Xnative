# Xnative

Application Android native de lecture de X/Twitter, en français, développée en Kotlin avec Jetpack Compose et Material 3. Les données viennent de **FxTwitter / FxEmbed**. Aucun compte X ni clé API n’est nécessaire.

## Fonctionnalités

- Fil chronologique fusionnant les comptes suivis localement, avec déduplication et filtre médias.
- Ajout et retrait de comptes par pseudo, jusqu’à 30 abonnements.
- Actualisation par bouton ou glissement vers le bas ; conservation du cache en cas d’erreur réseau.
- API JSON avec repli automatique vers RSS, ou mode RSS uniquement.
- Import d’une publication via un lien X, Twitter, FxTwitter ou FixupX.
- Réception des liens via le menu Android **Partager → Xnative**.
- Recherche dans les publications déjà chargées, favoris locaux et consultation hors connexion du texte.
- Avatars, images, citations, republications et compteurs lorsque la source les fournit.
- Détail de publication avec texte sélectionnable, partage, ouverture des médias et des interactions dans une application externe.
- Interface sombre, sauvegarde locale atomique et états de chargement, d’erreur et de fil vide.

## Installer et compiler

Prérequis : Android Studio récent, JDK 17 ou 21 et Android SDK 35. L’application fonctionne à partir d’**Android 8.0 (API 26)**.

1. Ouvrir ce dépôt dans Android Studio, puis laisser Gradle synchroniser les dépendances.
2. Installer le SDK Android 35 si Android Studio le demande.
3. Lancer `app` sur un appareil ou un émulateur.

En ligne de commande, définir `ANDROID_HOME` ou créer un `local.properties` non versionné contenant `sdk.dir=/chemin/vers/android-sdk`, puis :

```sh
./gradlew testDebugUnitTest lintDebug assembleDebug
```

APK de développement : `app/build/outputs/apk/debug/app-debug.apk`.

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Le workflow GitHub Actions **Android** exécute les tests, l’analyse Lint et la compilation, puis conserve l’APK dans l’artefact **Xnative-debug**. Une version destinée à la distribution doit être signée avec votre propre clé.

## Utilisation

Dans **Abonnements**, ajouter un pseudo, par exemple `NASA`. Revenir dans **Votre fil**, puis actualiser pour charger les publications récentes. En cas d’échec lors de l’ajout, l’abonnement reste enregistré afin de pouvoir réessayer. Le bouton de lien permet d’importer une publication, par exemple `https://x.com/jack/status/20`. Le marque-page conserve une copie dans **Favoris**. **Explorer** recherche uniquement dans le cache local.

Le réglage **Utiliser uniquement le RSS** s’applique lors de la prochaine actualisation. Les abonnements et favoris sont propres à Xnative et ne modifient pas un compte X.

## Sources FxTwitter

Endpoints utilisés :

| Usage | Endpoint |
| --- | --- |
| Publications récentes d’un compte | `https://api.fxtwitter.com/2/profile/{handle}/statuses?count=30&safe=1` |
| Flux RSS d’un compte | `https://fxtwitter.com/{handle}/feed.xml?count=30&safe=1` |
| Publication par identifiant | `https://api.fxtwitter.com/status/{id}` |

Références : [API FxEmbed](https://docs.fxembed.com/api/introduction), [flux RSS et Atom](https://docs.fxembed.com/guide/advanced/rss-atom-feeds/), [code FxEmbed](https://github.com/FxEmbed/FxEmbed).

Le premier chargement et l’actualisation nécessitent Internet. La disponibilité, les limites et l’exhaustivité des données dépendent du service tiers. Le fil charge une page récente par compte, sans pagination ; jusqu’à 150 publications sont conservées par abonnement et 300 imports au total. Les favoris sont conservés séparément. Les images sont mises en cache par Coil sans garantie de disponibilité hors connexion. Les vidéos s’ouvrent dans un lecteur externe. Le RSS n’inclut pas tous les compteurs et détails de l’API.

Xnative est un **lecteur** : publier, aimer, répondre, republier et s’authentifier sur X ne sont pas des fonctions de l’API publique FxTwitter utilisée ici. Ces actions sont accessibles par **Ouvrir sur X**. Il n’y a ni messagerie, ni notifications en arrière-plan, ni synchronisation entre appareils.

## Organisation

- `Data.kt` : modèles, validation des liens, parseurs JSON/RSS et client HTTP.
- `FeedViewModel.kt` : abonnements, import, actualisation et persistance locale.
- `MainActivity.kt` : navigation Compose, fil, recherche, favoris, abonnements et réception des partages.
- `FxClientTest.kt` : validation des liens, API v1/v2, fils groupés, RSS, repli réseau, limites HTTP et cache.

Les données locales sont stockées dans l’espace privé de l’application (`library.json`) ; aucune télémétrie n’est ajoutée. La désinstallation efface ces données. Les requêtes HTTP sont adressées à FxTwitter et aux hébergeurs des médias. Xnative est indépendant de X Corp et de FxTwitter.
