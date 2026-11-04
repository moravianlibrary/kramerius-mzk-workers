# Kramerius MZK workers

Worker images for MZK Kramerius deployments: the upstream `curator-worker` image plus the process plugins from `plugins/`.

Plugins compile against the libraries inside the base image, so only Docker is needed and the versions always match the worker.

## Build and push

```
make test
make build
make push IMAGE=<registry>/<group>/kramerius-mzk-worker
```

Log in to the registry first with `docker login`. Use `BASE_IMAGE=...` to build on another upstream version and `TAG=...` to override the image tag.

## Plugins

### external-refs

Harvests the latest `contentLocation` of externally managed datastreams from the object store into Solr.

#### Setup

Worker `configuration.properties`:

```properties
externalRefs.solrHost=http://<solr>:8983/solr/external-refs
objectStore.path=/data/akubra/objectStore
```

The worker needs the object store mounted. The collection is created from the configset in `solr/external-refs`, which must match the schema in the deployments repository.

Users without `a_process_edit` can only start the process if `plugins/external-refs/lp.xml` is mounted as `~/.kramerius4/lp.xml` on the Kramerius API pods.

#### Running

Start it with `POST /search/api/admin/v7.0/processes`:

```json
{"defid": "external-refs", "params": {"threads": "6"}}
```

All parameters are optional, and with none of them the run reads what changed since the last successful run. The first run has nothing to compare with, so it reads everything.

| Parameter | Default | Meaning |
|---|---|---|
| `threads` | CPU count minus 2, at least 1 | Number of FOXML reader threads, as a string |
| `ignoreDs` | `POLICY` | Comma-separated datastream IDs to skip |
| `full` | `false` | Ignore the last successful run and read everything. Cannot be combined with `since` |
| `paths` | whole store | Comma-separated path prefixes relative to the store root, see [Splitting a run](#splitting-a-run) |
| `since` | last successful run | Read files modified since this ISO-8601 instant, such as `2026-11-05T08:00:00Z`, minus an hour for clock differences. Cannot be combined with `full` |

A run fails straight away if `since` is invalid, or if a prefix in `paths` matches nothing in the store.

The last successful run is stored in the `__harvest_state__` document, so exclude it from queries with `-id:__harvest_state__`.

Corrupted FOXML files are logged as warnings and skipped, and are read again once the file changes. The run still completes and the process ends as `WARNING`, with the count in the process log. Storage read errors fail the run, and the next run repeats the same scan.

Solr timeouts, connection errors and 5xx responses are retried for about 30 minutes, and the scan slows down while it waits. If Solr stays away, or rejects a request with a 4xx, the run fails and the next run repeats the same scan.

#### Splitting a run

Reading the whole store takes one process a long time, and it has to start over if it fails. To split it, start several processes with different `paths`, so each one reads its own part.

A prefix is matched as text against the path relative to the store root. With the Akubra pattern `##/##/##`, directories are two hexadecimal characters:

| Prefix | Selects |
|---|---|
| `0` | `00` to `0f` |
| `0a` | `0a` |
| `0a/1` | `0a/10` to `0a/1f` |
| `00,01,02` | `00`, `01` and `02` |

A worker runs one process at a time and takes the next one from the queue when it is free. So you can start more parts than you have workers, and the parts spread out by themselves. If a part fails, only that part needs to run again.

A run with `paths` does not save the last successful run and does not flag orphaned refs, because it does not know about the rest of the store. Finish with one whole store run, or the next normal run reads everything again:

1. Set `replicas` of the worker group to the number of workers. Pods on one node share an NFS client, so spread them over nodes if the store is on NFS. Solr takes the combined load of all workers.
2. Write down the current time in UTC, then start the parts. This example queues 16 parts:

   ```bash
   date -u +%FT%TZ
   for part in 0 1 2 3 4 5 6 7 8 9 a b c d e f; do
     curl -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
       "$KRAMERIUS/search/api/admin/v7.0/processes" \
       -d '{"defid": "external-refs", "params": {"paths": "'"$part"'", "threads": "6"}}'
   done
   ```

3. Wait until all parts end as `FINISHED` or `WARNING`, and start any `FAILED` part again with the same `paths`.
4. Start one run without `paths`, with `since` set to the time from step 2. It reads what changed during the scan and saves the last successful run:

   ```json
   {"defid": "external-refs", "params": {"since": "2026-11-05T08:00:00Z"}}
   ```

After that, normal runs without parameters read only what changed.

#### Periodic refresh

The plugin has no schedule of its own. To keep the collection current, start a run without parameters periodically. This Kubernetes CronJob is an example to adapt, it has not been run as written:

```yaml
apiVersion: batch/v1
kind: CronJob
metadata:
  name: external-refs-refresh
spec:
  schedule: "0 1 * * *"
  concurrencyPolicy: Forbid
  jobTemplate:
    spec:
      backoffLimit: 0
      template:
        spec:
          restartPolicy: Never
          containers:
            - name: refresh
              image: <image with bash, curl and jq>
              envFrom:
                # KRAMERIUS, KEYCLOAK, CLIENT_ID, CLIENT_SECRET
                - secretRef:
                    name: external-refs-refresh
              command:
                - bash
                - -c
                - |
                  set -euo pipefail
                  token=$(curl -sf "$KEYCLOAK/realms/kramerius/protocol/openid-connect/token" \
                    -d grant_type=client_credentials -d "client_id=$CLIENT_ID" -d "client_secret=$CLIENT_SECRET" \
                    | jq -r .access_token)
                  api="$KRAMERIUS/search/api/admin/v7.0/processes"

                  for state in PLANNED RUNNING; do
                    active=$(curl -sf -H "Authorization: Bearer $token" "$api/batches?state=$state&limit=100" \
                      | jq '[.batches[].processes[] | select(.defid == "external-refs")] | length')
                    if [ "$active" -gt 0 ]; then
                      echo "A harvest is already $state, skipping"
                      exit 0
                    fi
                  done

                  curl -sf -H "Authorization: Bearer $token" -H "Content-Type: application/json" "$api" \
                    -d '{"defid": "external-refs", "params": {"threads": "6"}}'
```

- **Start after the first full build:** until a whole store run has saved the last successful run, a run without parameters reads everything.
- **Overlapping runs:** `concurrencyPolicy` only covers the job that submits the process, not the process itself, so the script asks the API whether a harvest is active.
- **Frequency:** every run walks the whole store to compare modification times, even when little changed. Check how long a run takes before choosing a schedule.
- **Failures:** a failed run does not save the last successful run, so the next run picks up its changes.
- **Removed refs:** these runs do not flag anything, so refs of deleted objects and removed datastreams stay as they are. To flag them, schedule an occasional `full` run without `paths`, which reads the whole store on one worker.

#### Results

As a side effect, a run finds corrupted FOXML files. A file counts as corrupted if it cannot be parsed as XML, or if an external datastream in it has no `ID`. Each one is logged with its PID, path and error, and the count is in the `Harvest finished` line:

```
WARNING: Skipping corrupted object uuid:... (/data/akubra/objectStore/...): org.xml.sax.SAXParseException...
```

To list them, search the process logs for `Skipping corrupted object`. To check every file, read the whole store with a first or `full` run, or with split runs covering all of it, because incremental runs read only changed files. A run does not check whether the content of a valid FOXML file is correct.

After a successful scan of the whole store (the first run, or `full`), refs the scan did not see get `orphaned=true`, except refs of corrupted objects and ignored datastreams, which it did not check. A ref that comes back resets to `false`. Incremental runs do not flag anything, because they do not read unchanged files. Split runs and `since` runs do not flag anything either, so run `full` without `paths` to flag refs in an existing collection.

### ndk-import

Imports a batch of FOXML like the stock `import` process, but first moves the `ALTO` and `TEXT_OCR` content that the FOXML only references as files into the FOXML itself. Akubra then stores that content in its datastream store at ingest, so no imported object keeps a `file:` reference into the import directory, and the batch can be deleted as soon as the process finishes.

NDK batches reference these streams as managed datastreams with a `file:` location:

```xml
<datastream ID="ALTO" CONTROL_GROUP="M" STATE="A" VERSIONABLE="false">
  <datastreamVersion ID="ALTO.0" CREATED="..." MIMETYPE="text/plain">
    <contentLocation TYPE="URL" REF="file:/kramerius_ndk/import/<batch>/alto_<batch>_0001.xml.alto"/>
  </datastreamVersion>
</datastream>
```

The stock import stores such a datastream as a reference, so the object breaks once the batch is removed.

#### Setup

Worker `configuration.properties`:

```properties
# relative inputDataDir with pathRoot=ndk resolves under this directory
ndk.import.root=/data/import/ndk
# rewritten copies go to <workDir>/<process uuid>/; must not be inside ndk.import.root
ndk.import.workDir=/data/import/ndk-work
```

The worker needs the import volumes mounted read-write, including `workDir`. Users without `a_process_edit` can only start the process if `plugins/ndk-import/lp.xml` is mounted as `~/.kramerius4/lp.xml` on the Kramerius API pods. The process needs `a_import`.

#### Configuration

| Key | Default | Meaning |
|---|---|---|
| `ndk.import.root` | required | Base directory for relative paths with `pathRoot=ndk` |
| `ndk.import.workDir` | required | Where rewritten copies are written, one directory per process |
| `ndk.import.pathMap` | none | `;`-separated `from=>to` prefixes applied to absolute paths and to file references, for batches that name an older mount point, such as `/kramerius_ndk/import/=>/data/import/ndk/` |
| `ndk.import.internalize` | `ALTO,TEXT_OCR` | Datastream IDs whose file references are moved into the FOXML |
| `ndk.import.keepWorkDirOnFailure` | `true` | Keep the rewritten copy when the import or the verification fails |
| `ndk.import.lockStaleHours` | `24` | Age after which another run may take over a batch lock |
| `import.directory` | stock | Base for relative paths without `pathRoot` |

Every path, after `pathMap`, must lie under `ndk.import.root` or `import.directory`, including file references and through symlinks.

#### Parameters

Start it with `POST /search/api/admin/v7.0/processes`:

```json
{"defid": "ndk_import", "params": {"inputDataDir": "<batch uuid>", "startIndexer": "true", "pathRoot": "ndk"}}
```

| Parameter | Default | Meaning |
|---|---|---|
| `inputDataDir` | required | Batch directory, relative or absolute |
| `startIndexer` | required | As in the stock `import` |
| `license`, `collections` | none | As in the stock `import` |
| `indexationType` | `ingest.indexType` | As in the stock `import` |
| `pathtype` | `relative` | `relative` or `absolute` |
| `pathRoot` | `import.directory` | `ndk` resolves relative paths under `ndk.import.root` |
| `externalStreams` | `internalize` | `internalize`; `keep` imports the batch as the stock `import` would; `check` only verifies already imported objects |
| `dryRun` | `false` | Resolve, lock and check the batch, ingest nothing |

#### How a run works

1. **Resolve** `inputDataDir` and refuse anything outside the configured roots.
2. **Lock** the batch with `<batch>/.ndk-import.lock`, so a second run of the same batch fails straight away.
3. **Check** every FOXML: list the `ALTO`/`TEXT_OCR` file references and fail before ingesting anything if any file is missing or outside the roots. File references in other datastreams are logged as warnings and left alone.
4. **Rewrite** the FOXML into `<workDir>/<process uuid>/`, with each listed reference replaced by its content as base64 `binaryContent`. Everything else in the FOXML stays as it was; objects without such references are copied as they are.
5. **Import** the rewritten copy with the stock `Import`.
6. **Verify** that each of those datastreams is now stored as managed content and no longer points into an import root.
7. **Clean up** the rewritten copy and remove the lock. The batch itself is never changed.

After a failed import or verification, the rewritten copy stays in `workDir` (with `keepWorkDirOnFailure=true`), and the batch can be run again: objects that already exist are handled as the stock import handles them.
