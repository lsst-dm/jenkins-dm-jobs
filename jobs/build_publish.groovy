import util.Plumber

def p = new Plumber(name: 'release/build-publish', dsl: this)
p.pipeline().with {
  description('Run release/run-rebuild & release/rub-publish in series')

  parameters {
    stringParam('REFS', null, 'Whitespace delimited list of "refs" to attempt to build.  Priority is highest -> lowest from left to right.  "master" is implicitly appended to the right side of the list, if not specified.')
    stringParam('PRODUCT', null, 'Whitespace delimited list of EUPS products to build.')
    stringParam('EUPS_TAG', null, 'EUPS tag string. Note that EUPS does not deal well with "." or "-" as part of a tag')
    booleanParam('SKIP_DOCS', false, 'Do not build and publish documentation.')
  }
}
