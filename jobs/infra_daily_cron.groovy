import util.Plumber

def p = new Plumber(name: 'sqre/infra/infra-daily-cron', dsl: this)
p.pipeline().with {
  description('Periodic builds of infra jobs.')

  triggers {
    cron('H H * * *')
  }
}
