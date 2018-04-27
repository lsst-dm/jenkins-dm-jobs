import util.Plumber

def p = new Plumber(name: 'sqre/infrastructure/infra-daily', dsl: this)
p.pipeline().with {
  description('Periodic builds of infrastructure jobs.')
}
