import util.Plumber

def p = new Plumber(name: 'release/weekly-release', dsl: this)
p.pipeline().with {
  description('Tag and release the DM pipelines/dax "weekly".')

  parameters {
    stringParam('YEAR', null, 'Gregorian calendar year.')
    stringParam('WEEK', null, 'Week of Gregorian calendar year.')
  }
}
