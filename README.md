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

Worker `configuration.properties`:

```properties
externalRefs.solrHost=http://<solr>:8983/solr/external-refs
objectStore.path=/data/akubra/objectStore
```

The worker needs the object store mounted. The collection is created from the configset in `solr/external-refs`, which must match the schema in the deployments repository.

Users without `a_process_edit` can only start the process if `plugins/external-refs/lp.xml` is mounted as `~/.kramerius4/lp.xml` on the Kramerius API pods.

Start it with `POST /search/api/admin/v7.0/processes`:

```json
{"defid": "external-refs", "params": {"threads": "4"}}
```

| Parameter | Default | Meaning |
|---|---|---|
| `threads` | CPU count minus 2, at least 1 | Number of FOXML reader threads, as a string |
| `ignoreDs` | `POLICY` | Comma-separated datastream IDs to skip |
| `full` | `false` | Ignore the last successful run and scan everything |

The last successful run is stored in the `__harvest_state__` document, so exclude it from queries with `-id:__harvest_state__`.

Corrupted FOXML files are logged as warnings and skipped, and are read again once the file changes. The run still completes and the process ends as `WARNING`, with the count in the process log. Storage read errors fail the run, and the next run repeats the same scan.

Solr timeouts, connection errors and 5xx responses are retried for about 30 minutes, and the scan slows down while it waits. If Solr stays away, or rejects a request with a 4xx, the run fails and the next run repeats the same scan.

After a successful scan of the whole store (the first run, or `full`), refs the scan did not see get `orphaned=true`, except refs of corrupted objects and ignored datastreams, which it did not check. A ref that comes back resets to `false`. Incremental runs do not flag anything, because they do not read unchanged files.
