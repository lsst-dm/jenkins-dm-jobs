import util.Plumber

def p = new Plumber(name: 'qserv/release/tag-latest+dev', dsl: this)
p.pipeline()
