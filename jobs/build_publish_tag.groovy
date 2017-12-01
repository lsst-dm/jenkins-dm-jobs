import util.Plumber

def p = new Plumber(name: 'release/build-publish-tag', dsl: this)
p.pipeline().with {
  parameters {
    stringParam('BRANCH', null, 'Whitespace delimited list of "refs" to attempt to build.  Priority is highest -> lowest from left to right.  "master" is implicitly appended to the right side of the list, if not specified.')
    stringParam('PRODUCT', null, 'Whitespace delimited list of EUPS products to build.')
    stringParam('GIT_TAG', null, 'git tag string. Will be automatically converted into an EUPS compatible format for publishing products. Eg. w.2016.08 -> w_2016_08')
    booleanParam('SKIP_DEMO', false, 'Do not run the demo after all packages have completed building.')
    booleanParam('SKIP_DOCS', false, 'Do not build and publish documentation.')
  }
}
