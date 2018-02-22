import util.Plumber

def p = new Plumber(name: 'sqre/infrastructure/infra-weekly-cron', dsl: this)
p.pipeline().with {
  description('Periodic weekly builds of infrastructure jobs.')

  triggers {
    cron('H H * * 0')
  }
}
