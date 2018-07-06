import util.Plumber

def p = new Plumber(name: 'sqre/infra/infra-monthly-cron', dsl: this)
p.pipeline().with {
  description('Periodic monthly builds of infra jobs.')

  triggers {
    cron('0 0 1 * *')
  }
}
