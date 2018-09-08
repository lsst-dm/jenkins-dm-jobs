import util.Plumber

def p = new Plumber(name: 'release/run-rebuild', dsl: this)
p.pipeline().with {
  description('run rebuild in the canonical LSST DM build environment.  Only us this job when preparing to publish EUPS distrib packages.')

  parameters {
    stringParam('BRANCH', null, 'Whitespace delimited list of "refs" to attempt to build.  Priority is highest -> lowest from left to right.  "master" is implicitly appended to the right side of the list, if not specified.')
    stringParam('PRODUCT', null, 'Whitespace delimited list of EUPS products to build.')
    booleanParam('SKIP_DOCS', false, 'Do not build and publish documentation.')
    booleanParam('PREP_ONLY', false, 'Pass -p flag to lsstsw/bin/rebuild -- prepare git clones/a manifest but do not build products.')
    stringParam('TIMEOUT', '8', 'build timeout in hours')
    // enable for debugging only
    // booleanParam('NO_VERSIONDB_PUSH', true, 'Skip push to remote versiondb repo.')
  }
}
