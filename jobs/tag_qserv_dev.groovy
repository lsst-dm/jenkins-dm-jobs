import util.Plumber

def p = new Plumber(name: 'qserv/release/tag-qserv-dev', dsl: this)
p.pipeline()
