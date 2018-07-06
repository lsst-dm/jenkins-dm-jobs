import util.Plumber

def p = new Plumber(name: 'sqre/infra/infra-weekly-cron', dsl: this)
p.pipeline().with {
  description('Periodic weekly builds of infra jobs.')

  triggers {
    cron('H H * * 0')
  }
}
