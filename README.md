# jenkins-dm-jobs

When updating a job in this repo, for example when changing the user-specified
parameters but _not_ when changing the pipeline to be run, manually trigger
the sqre/seeds/dm-jobs job to rebuild the interface.

## Linting

For linting we are using [npm-groovy-lint](https://github.com/nvuillam/npm-groovy-lint),
but any ide should be able to use the .groovylintrc to follow the correct formating
