jenkins-dm-jobs
===

When updating a job in this repo, for example when changing the user-specified
parameters but _not_ when changing the pipeline to be run, manually trigger
the sqre/seeds/dm-jobs job to rebuild the interface.

Unit tests
---

The pure helpers in `pipelines/lib/` have unit tests that run outside Jenkins:

```bash
./gradlew test
```

See `src/test/README.md` for what is covered and how to add a spec.
