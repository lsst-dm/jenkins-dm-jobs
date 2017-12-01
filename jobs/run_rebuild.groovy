import util.Plumber

def p = new Plumber(name: 'release/run-rebuild', dsl: this)
p.pipeline().with {
  description('run rebuild in the canoncial LSST DM build environment.  Only us this job when preparing to publish EUPS distrib packages.')

  parameters {
    stringParam('BRANCH', null, 'Whitespace delimited list of "refs" to attempt to build.  Priority is highest -> lowest from left to right.  "master" is implicitly appended to the right side of the list, if not specified.')
    stringParam('PRODUCT', null, 'Whitespace delimited list of EUPS products to build.')
    booleanParam('SKIP_DEMO', false, 'Do not run the demo after all packages have completed building.')
    booleanParam('SKIP_DOCS', false, 'Do not build and publish documentation.')
    stringParam('TIMEOUT', '6', 'build timeout in hours')
    // enable for debugging only
    // booleanParam('NO_VERSIONDB_PUSH', true, 'Skip push to remote versiondb repo.')
  }
}
