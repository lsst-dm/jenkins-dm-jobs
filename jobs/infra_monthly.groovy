import util.Plumber

def p = new Plumber(name: 'sqre/infrastructure/infra-monthly', dsl: this)
p.pipeline().with {
  description('Periodic monthly builds of infrastructure jobs.')
}
