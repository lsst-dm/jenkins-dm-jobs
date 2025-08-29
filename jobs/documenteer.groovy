import util.Plumber

def p = new Plumber(name: 'sqre/infra/documenteer', dsl: this)
p.pipeline().with {
  description('Build and publish documenteer/sphinx based docs.')
  
  logRotator {
    daysToKeep(60)
    artifactDaysToKeep(60)
  }
  
  parameters {
    stringParam('EUPS_TAG', null, '(required) EUPS distrib tag name. Eg. w_9999_52')
    stringParam('LTD_SLUG', null, '(required) LTD edition slug')
    stringParam('TEMPLATE_REPO', 'lsst/pipelines_lsst_io', 'github repo slug')
    stringParam('TEMPLATE_REF', 'main', 'git repo ref')
    stringParam('RELEASE_IMAGE', null, '(optional) Explicit name of release docker image including tag.')
    booleanParam('PUBLISH', true, 'Publish documenteer docs.')
  }
}
