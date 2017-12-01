import util.Plumber

def p = new Plumber(name: 'sqre/infrastructure/infra-monthly-cron', dsl: this)
p.pipeline().with {
  description('Periodic monthly builds of infrastructure jobs.')

  triggers {
    cron('0 0 1 * *')
  }
}
