import util.Plumber

def p = new Plumber(name: 'release/test-nightly-release', dsl: this)
p.pipeline().with {
  description('Tag and release the DM pipelines/dax "nightly" (TEST).')

  parameters {
    stringParam('YEAR', null, 'Gregorian calendar year.')
    stringParam('MONTH', null, 'Gregorian calendar month.')
    stringParam('DAY', null, 'Gregorian day of calendar month.')
  }
}
