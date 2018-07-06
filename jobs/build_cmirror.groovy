import util.Plumber

def p = new Plumber(name: 'sqre/infra/build-cmirror', dsl: this)
p.pipeline().with {
  description('Constructs lsstsqre/cmirror container.')
}
