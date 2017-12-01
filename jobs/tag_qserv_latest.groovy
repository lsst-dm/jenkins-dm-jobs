import util.Plumber

def p = new Plumber(name:'qserv/release/tag-qserv_latest', dsl: this)
p.pipeline()
