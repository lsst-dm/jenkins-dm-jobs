import util.Plumber

def p = new Plumber(name: 'sqre/infrastructure/documenteer', dsl: this)
p.pipeline().with {
  description('Build and publish documenteer/sphinx based docs.')

  parameters {
    stringParam('EUPS_TAG', null, 'EUPS distrib tag name. Eg. w_2017_45')
  }
}
