import util.Plumber

def p = new Plumber(name: 'sqre/infrastructure/build-cmirror', dsl: this)
p.pipeline().with {
  description('Constructs lsstsqre/cmirror container.')
}
