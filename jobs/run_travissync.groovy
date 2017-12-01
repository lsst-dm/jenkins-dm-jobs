import util.Plumber

def p = new Plumber(name: 'sqre/infrastructure/travissync', dsl: this)
p.pipeline().with {
  description('Synchronize Travis CI with GitHub.')

  triggers {
    cron('H * * * *')
  }
}
