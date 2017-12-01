import util.Plumber

def p = new Plumber(name: 'qserv/docker/build-dev', dsl: this)
p.pipeline()
