import util.Plumber

def p = new Plumber(name: 'sqre/infrastructure/documenteer', dsl: this)
p.pipeline().with {
  description('Build and publish documenteer/sphinx based docs.')

  parameters {
    stringParam('EUPS_TAG', null, '(required) EUPS distrib tag name. Eg. w_2017_45')
    stringParam('LTD_SLUG', null, '(required) LTD edition slug')
    stringParam('TEMPLATE_REPO', 'lsst/pipelines_lsst_io', 'github repo slug')
    stringParam('TEMPLATE_REF', 'tickets/DM-11216', 'git repo ref')
    booleanParam('PUBLISH', true, 'Publish documenteer docs.')
  }
}
