# PII Protector

Application REST avec Spring Boot 4.1.1, Java 21, Bouncy Castle et stockage JPA. Les champs `name` et `email` sont chiffrés avec FF1/AES-256 avant leur écriture en base de données. FF1 conserve la forme et la longueur des valeurs mais ne fournit pas d'authentification ni d'intégrité.

## Démarrer une base de données

Chaque script démarre uniquement le serveur sélectionné. Au premier démarrage d'un volume vierge, son script SQL crée un schéma relationnel de carnet d'adresses : `contacts`, `contact_addresses`, `contact_phones`, `contact_emails` et `contact_relationships`. Chaque table contient 10 millions de lignes synthétiques, avec clés primaires/étrangères, contraintes métier et index de recherche :

```bash
docker compose up -d keycloak
./bin/start-postgres.sh
./bin/connect-postgres.sh
./bin/start-sqlserver.sh
./bin/start-oracle.sh
./bin/start-db2.sh
```

`./bin/connect-postgres.sh` ouvre une session interactive `psql` dans le conteneur PostgreSQL. Il n'y a pas de mot de passe à saisir.

Les scripts SQL sont dans `bin/sql/`. PostgreSQL et Oracle les exécutent via leurs hooks d'initialisation, SQL Server via son service d'initialisation Compose et DB2 via un service qui attend que le serveur soit prêt. Les hooks natifs PostgreSQL et Oracle ne sont exécutés que pour un volume de données vierge. SQL Server et DB2 vérifient également les tables déjà initialisées pour éviter de recréer les données au redémarrage.

Ces scripts d'initialisation ne migrent pas un volume créé avec une ancienne version du schéma. Avant d'utiliser l'API de chiffrement sur un tel volume, il faut migrer les colonnes vers les types/longueurs de la version actuelle; ne supprimez pas un volume contenant des données à préserver.

Le schéma utilise des noms de colonnes métier ordinaires (`display_name`, `email_address`, `phone_number`, `street`, etc.), comme une base de carnet d'adresses classique. Les lignes chargées contiennent des valeurs synthétiques reconnaissables (noms « Contact », courriels `example.test`, notes synthétiques), pas de vraies PII. L'API de chiffrement peut ainsi être testée dès l'initialisation.

Pour démarrer les quatre serveurs à la fois, exécuter `docker compose up -d`. Chaque base contient 50 millions de lignes synthétiques (200 millions au total pour les quatre bases) : l'initialisation peut être très longue et réclamer plusieurs dizaines de Go d'espace disque. Prévoir suffisamment de mémoire et d'espace avant de lancer l'ensemble.

Les fichiers de données persistent sur l'hôte dans `docker_volumes/postgres/`, `docker_volumes/sqlserver/`, `docker_volumes/oracle/` et `docker_volumes/db2/`. Le dossier `docker_volumes/` est ignoré par Git. Ne supprimez pas ces dossiers pendant qu'une base tourne ou si ses données doivent être conservées.

Pour rejouer l'initialisation PostgreSQL ou Oracle, il faut réinitialiser les fichiers de données de la base concernée; cela supprime toutes ses données. Mots de passe et fichiers de données du Compose sont prévus pour du développement uniquement. Sur Mac Apple Silicon, SQL Server et DB2 tournent en émulation `linux/amd64`.

## Configurer et démarrer l'application

Les paramètres de connexion et la clé sont lus par `src/main/resources/application.properties`. PostgreSQL est la base par défaut. Générer une clé AES de 256 bits (32 octets encodés en Base64), puis démarrer l'application dans le même terminal :

```bash
docker compose up -d keycloak
export PII_ENCRYPTION_KEY="$(openssl rand -base64 32)"
mvn spring-boot:run
```

La propriété `pii.encryption.key` peut aussi être renseignée directement dans `application.properties`. Ne jamais committer une vraie clé : sa perte empêche le déchiffrement, et sa divulgation expose les données. Prévoir un gestionnaire de secrets et une procédure de rotation pour un déploiement réel.

Les routes d'administration de chiffrement sont configurées comme un OAuth2 Resource Server. Elles valident les JWT Bearer avec les clés publiques du realm Keycloak et exigent le scope `pii.jobs`. Compose importe le realm `pii-protector` depuis `docker/keycloak/pii-protector-realm.json`, expose Keycloak sur `http://localhost:8181`, et configure le client confidentiel de service `pii-protector-admin` avec le scope requis. Les identifiants `admin`/`admin` et le secret `pii-protector-dev-only-secret` sont réservés au développement local; remplacez-les avant tout usage réel. Si Keycloak est exposé à une autre adresse, `OAUTH2_ISSUER_URI` doit correspondre exactement à l'émetteur des jetons et rester accessible par l'application. Le nom du scope peut être changé via `OAUTH2_REQUIRED_SCOPE`.

Pour obtenir un jeton d'accès client (client credentials) utilisable par l'API :

```bash
export OAUTH2_ACCESS_TOKEN="$(
  curl -fsS -X POST http://localhost:8181/realms/pii-protector/protocol/openid-connect/token \
    -H 'Content-Type: application/x-www-form-urlencoded' \
    -d 'grant_type=client_credentials' \
    -d 'client_id=pii-protector-admin' \
    -d 'client_secret=pii-protector-dev-only-secret' \
    -d 'scope=pii.jobs' | jq -r .access_token
)"
```

Les propriétés `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` et `DB_DRIVER` surchargent la connexion configurée dans `application.properties` :

| Base | `DB_URL` | `DB_USERNAME` | `DB_PASSWORD` | `DB_DRIVER` |
|---|---|---|---|---|
| PostgreSQL (défaut) | `jdbc:postgresql://localhost:5432/pii_protector` | `pii` | `pii_dev_only` | `org.postgresql.Driver` |
| SQL Server | `jdbc:sqlserver://localhost:1433;databaseName=pii_protector;encrypt=true;trustServerCertificate=true` | `sa` | `PiiProtector!Dev2026` | `com.microsoft.sqlserver.jdbc.SQLServerDriver` |
| Oracle Free | `jdbc:oracle:thin:@//localhost:1521/FREEPDB1` | `pii` | `pii_dev_only` | `oracle.jdbc.OracleDriver` |
| DB2 | `jdbc:db2://localhost:50000/pii_protector` | `db2inst1` | `PiiProtector!Dev2026` | `com.ibm.db2.jcc.DB2Driver` |

Exemple pour choisir Oracle :

```bash
export DB_URL='jdbc:oracle:thin:@//localhost:1521/FREEPDB1'
export DB_USERNAME=pii
export DB_PASSWORD=pii_dev_only
export DB_DRIVER=oracle.jdbc.OracleDriver
export PII_ENCRYPTION_KEY="$(openssl rand -base64 32)"
mvn spring-boot:run
```

## API

- `POST /api/pii` — enregistre un nom et un courriel chiffrés.
- `GET /api/pii/{id}` — récupère et déchiffre un enregistrement.
- `POST /api/admin/encryption-jobs` — lance en arrière-plan le chiffrement des valeurs existantes en clair dans les tables et colonnes transmises.
- `GET /api/admin/encryption-jobs` — liste les traitements en cours et en attente, leur base, ainsi que le nombre de lignes prévu et traité par table.
- `GET /api/admin/encryption-jobs/{id}` — suit l'état du traitement et le nombre de valeurs chiffrées.

Le lancement et le suivi des tâches exigent un jeton Bearer portant le scope `pii.jobs`, envoyé dans l'en-tête `Authorization: Bearer <access-token>`. Exemple d'appel au service de jeton Keycloak :

```bash
curl -X POST http://localhost:8181/realms/pii-protector/protocol/openid-connect/token \
  -H 'Content-Type: application/x-www-form-urlencoded' \
  -d 'grant_type=client_credentials' \
  -d 'client_id=pii-protector-admin' \
  -d 'client_secret=pii-protector-dev-only-secret' \
  -d 'scope=pii.jobs'
```

Chaque élément de `tables` précise une table, ses colonnes à chiffrer et, facultativement, son schéma et `maxRows`. `maxRows` limite indépendamment le nombre de lignes à traiter pour cette table; omis, il n'y a pas de limite. La limite porte sur les lignes contenant au moins une valeur en clair ciblée; les anciennes valeurs AES-GCM au format `v1:` sont ignorées. FF1 ne porte aucun marqueur : toute relance rechiffre donc les valeurs FF1 déjà présentes. Lance le traitement une seule fois sur des données claires. L'identifiant unique à clé primaire entière est détecté par les métadonnées JDBC; les colonnes de données doivent être de type texte. Le traitement peut viser les tables accessibles par la connexion choisie. `batchSize` est facultatif (1000 par défaut, maximum 10000).

La propriété optionnelle `connection` de la requête permet de configurer une connexion JDBC propre à chaque traitement. Sans elle, l'application utilise la connexion par défaut définie dans `application.properties`. Les pilotes admis sont PostgreSQL, Oracle, SQL Server et DB2; le pilote doit correspondre au préfixe de l'URL JDBC. Une connexion indépendante et son pool sont créés pour chaque tâche puis fermés à sa fin. Deux tâches peuvent s'exécuter en parallèle; jusqu'à dix autres peuvent attendre dans la file, les demandes au-delà recevant `503 Service Unavailable`. Le mot de passe est transmis dans le corps de la requête et n'est pas renvoyé dans le statut : utilise HTTPS, ne place pas le secret dans l'URL et configure les journaux du serveur/proxy pour ne jamais enregistrer le corps de cette requête.

Par exemple, ces deux appels démarrent des traitements indépendants, le premier vers PostgreSQL et le second vers Oracle :

```json
{
  "connection": {
    "url": "jdbc:postgresql://localhost:5432/pii_protector",
    "username": "pii",
    "password": "<secret PostgreSQL>",
    "driver": "org.postgresql.Driver"
  },
  "tables": [
    {"schema":"public","name":"contacts","columns":["display_name"],"maxRows":10000}
  ]
}
```

```json
{
  "connection": {
    "url": "jdbc:oracle:thin:@//localhost:1521/FREEPDB1",
    "username": "pii",
    "password": "<secret Oracle>",
    "driver": "oracle.jdbc.OracleDriver"
  },
  "tables": [
    {"schema":"PII","name":"CONTACTS","columns":["DISPLAY_NAME"],"maxRows":10000}
  ]
}
```

```bash
curl -X POST http://localhost:8080/api/admin/encryption-jobs \
  -H "Authorization: Bearer $OAUTH2_ACCESS_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{
    "tables": [
      {"schema":"public","name":"contacts","columns":["display_name","date_of_birth"],"maxRows":50000},
      {"schema":"public","name":"contact_emails","columns":["email_address"]}
    ],
    "batchSize":1000
  }'
```

La réponse `202 Accepted` fournit l'URL de suivi dans l'en-tête `Location`. Interroger cette URL avec le même jeton jusqu'à l'état `SUCCEEDED` ou `FAILED`; la réponse indique également le type de base ciblé, sans informations d'identification. La route de suivi global retourne seulement les tâches `RUNNING` et `QUEUED`, avec les nombres de tâches en cours et en attente. `totalRowsByTable` est une estimation du nombre de lignes contenant des valeurs candidates au moment de la soumission (plafonnée par `maxRows`); `processedRowsByTable` est le nombre déjà traité. Le chiffrement se fait par lots. `parallelism` règle le nombre de workers JDBC dans un job (4 par défaut, maximum 8); chaque worker traite des lots disjoints de clés primaires, et `maxRows` reste la limite totale par table. Chaque worker utilise sa propre connexion et emprunte un moteur FF1 préinitialisé dans le pool partagé. Plusieurs jobs peuvent aussi tourner en parallèle; choisis une parallélisation modérée pour ne pas saturer les connexions, le CPU ou les écritures de la base. L'état de suivi est conservé en mémoire et sera perdu si l'application redémarre. Les valeurs FF1 gardent leur longueur; assure-toi que le domaine de chaque alphabet et la longueur des valeurs conviennent avant le lancement.

Exemple :

```bash
curl -X POST http://localhost:8080/api/pii \
  -H 'Content-Type: application/json' \
  -d '{"name":"Alice Example","email":"alice@example.org"}'
```

Seules les routes d'administration du chiffrement sont protégées par OAuth2 et le scope `pii.jobs`. L'API métier de démonstration `/api/pii` reste publique; ne l'exposez pas en production sans définir aussi les règles d'authentification et d'autorisation adaptées.

## Tests

```bash
mvn test
```

## Tests de performance de l'API

Les suivis persistants du projet sont disponibles dans [les exigences futures de l'API](docs/future-encryption-api-requirements.md) et le [journal des expériences de performance](docs/performance-experiments.md).

Le chiffrement des nouvelles données utilise FF1 avec AES-256. Les quatre moteurs FF1 (deux alphabets et deux directions) sont initialisés au démarrage de l'application puis réutilisés; leurs appels sont synchronisés pour rester sûrs lorsque plusieurs traitements tournent en parallèle. Par défaut, les caractères alphanumériques ASCII sont chiffrés dans leur alphabet commun; `date_of_birth`, `phone_number` et `extension` utilisent l'alphabet numérique. Les espaces et séparateurs sont conservés. La valeur chiffrée a la même longueur et n'a aucun marqueur de format. Le tweak FF1 est vide et fixe : le nom de colonne ne lie plus le chiffrement à un champ, et deux champs utilisant le même alphabet chiffrent la même entrée de façon identique. Ce changement rend les valeurs chiffrées avec le tweak précédent incompatibles : elles ne peuvent pas être déchiffrées avec le nouveau moteur; ne déployez pas ce changement sur des valeurs FF1 existantes sans plan de régénération/migration. FF1 exige un domaine d'au moins un million de valeurs : les séquences de moins de 4 caractères alphanumériques (ou de moins de 6 chiffres pour l'alphabet numérique) restent inchangées. Les caractères non ASCII restent inchangés. FF1 ne fournit pas d'authentification/intégrité.

Librairies courantes selon le besoin :

- **Gatling** — DSL Java/Scala, scénarios HTTP reproductibles, charge progressive et rapports HTML; intégré ici via Maven.
- **k6** — scripts JavaScript légers, pratique en CI et facile à exécuter avec Docker.
- **Apache JMeter** — interface graphique et large éventail de protocoles, pratique pour créer des plans sans beaucoup coder.
- **JMH** — référence pour mesurer le coût isolé du chiffrement Java; ne mesure ni HTTP, ni JDBC, ni la base de données.

La simulation Gatling de ce projet mesure le parcours réel « démarrer une tâche, puis sonder son état jusqu'à la fin » avec un utilisateur. L'API accepte deux tâches en parallèle; une simulation Gatling concurrente n'est pas encore configurée. Elle rapporte les temps de réponse HTTP, la durée du parcours complet et l'échec d'une tâche. Elle n'est pas exécutée par `mvn test`.

Démarrer l'application contre la base à tester en définissant `PII_ENCRYPTION_KEY` et `OAUTH2_ISSUER_URI`, puis obtenir un jeton OAuth2 d'accès avec le scope `pii.jobs`. Transmettre ce jeton à Gatling et exécuter depuis la racine du projet :

```bash
export OAUTH2_ACCESS_TOKEN='<access-token obtenu depuis Keycloak>'
mvn gatling:test \
  -DbaseUrl=http://localhost:8080 \
  -DtablesJson='{"tables":[{"schema":"public","name":"contacts","columns":["display_name","date_of_birth"]}],"batchSize":1000}'
```

`tablesJson` est le JSON complet attendu par `POST /api/admin/encryption-jobs`; adapte le schéma, la table, les colonnes et la taille du lot au moteur et aux données en place. Ajoute `maxRows` à chaque objet de table pour limiter le nombre de lignes à traiter indépendamment par table. Pour transmettre le corps JSON sans le citer dans la ligne Maven, définissez `PII_ENCRYPTION_TABLES_JSON`. `-DmaxPolls` configure le nombre maximal de sondages (une seconde entre chaque, 14 400 par défaut). Le jeton d'accès est lu depuis `OAUTH2_ACCESS_TOKEN` (ou `-DaccessToken`). Le rapport Gatling est généré sous `target/gatling/`.

La simulation chiffre réellement les lignes claires de la base ciblée. Le premier essai inclut ce travail; les suivants ignorent les lignes déjà chiffrées. Pour comparer des exécutions, restaure un jeu de données de test clair entre les essais et garde constantes la base, la taille du jeu, le lot et la charge machine. Ne lance pas ce test contre des données de production.
