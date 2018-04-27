import util.Plumber

def p = new Plumber(name: 'sqre/infrastructure/infra-daily-cron', dsl: this)
p.pipeline().with {
  description('Periodic builds of infrastructure jobs.')

  triggers {
    cron('H H * * *')
  }
}
