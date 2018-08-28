import util.Plumber

def p = new Plumber(name: 'sims/weekly-release', dsl: this)
p.pipeline().with {
  description('Tag and release sims "weekly".')

  parameters {
    stringParam('YEAR', null, 'Gregorian calendar year.')
    stringParam('WEEK', null, 'Week of Gregorian calendar year.')
    stringParam('LSST_DISTRIB_GIT_TAG', null, 'lsst_distrib weekly release git tag.')
  }
}
