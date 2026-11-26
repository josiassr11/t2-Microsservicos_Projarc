#!/usr/bin/env python3
"""
Fakes do iEducar e do Moodle para testar o T2 sem esperar os sistemas reais subirem.

- Só biblioteca padrão do Python 3 (sem pip).
- Imita os MESMOS formatos que o T1 já consumia (ver docs/ieducar-api.md e docs/moodle-api.md do T1).
- Estado em memória; reiniciar o processo volta aos dados iniciais.

Uso:
    python fake_sistemas.py                       # iEducar na 80 e Moodle na 8080
    python fake_sistemas.py --ieducar-port 18080 --moodle-port 18081

Endpoints administrativos (GET, existem nas DUAS portas; agem sobre o sistema daquela porta):
    /__admin/estado                         estado atual (JSON)
    /__admin/down?seconds=N[&modo=503|lento]  derruba o sistema por N segundos (0 = até chamar /up).
                                            modo=503 (padrão) responde 503; modo=lento dorme 30s antes de responder
    /__admin/up                             volta ao normal
    /__admin/reset                          restaura os dados iniciais
  Somente na porta do iEducar:
    /__admin/renomear-turma?id=3&nome=Novo+Nome
    /__admin/adicionar-aluno?turma=3&nome=Fulano+de+Tal[&email=..]
    /__admin/cancelar-matricula?aluno=3&turma=3
    /__admin/inativar-turma?id=3             (ativa=0)   /__admin/ativar-turma?id=3
    /__admin/adicionar-professor?turma=3&nome=Prof+Novo[&email=..]
    /__admin/remover-vinculo?servidor=13&turma=3
    /__admin/nova-turma?curso=1&nome=Turma+Z
"""
import argparse
import json
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

ANO = 2026


# --------------------------------------------------------------------------------------------
# Dados iniciais
# --------------------------------------------------------------------------------------------
def dados_iniciais_ieducar():
    return {
        "escola": {"id": 2, "sigla": "EDC", "nome": "Escola de Tecnologia - Campus Central", "pessoa_id": 4},
        "cursos": {
            1: {"id": 1, "nome": "Engenharia de Software", "ativo": 1},
            2: {"id": 2, "nome": "Análise e Desenvolvimento de Sistemas", "ativo": 1},
        },
        "turmas": {
            3: {"id": 3, "nome": "Turma A", "sigla": "A", "ano": ANO, "ativo": 1, "curso_id": 1, "serie_id": 3},
            4: {"id": 4, "nome": "Turma B", "sigla": "B", "ano": ANO, "ativo": 1, "curso_id": 1, "serie_id": 3},
            5: {"id": 5, "nome": "Turma A", "sigla": "A", "ano": ANO, "ativo": 1, "curso_id": 2, "serie_id": 4},
        },
        "alunos": {
            1: {"id": 1, "nome": "Ana Beatriz Souza", "email": "ana.souza@aluno.demo.edu.br", "cpf": "100.000.001-08"},
            2: {"id": 2, "nome": "Bruno Henrique Lima", "email": "bruno.lima@aluno.demo.edu.br", "cpf": "100.000.002-80"},
            3: {"id": 3, "nome": "Carla Mendes Ferreira", "email": "carla.ferreira@aluno.demo.edu.br", "cpf": "100.000.003-61"},
            4: {"id": 4, "nome": "Diego Almeida Rocha", "email": "diego.rocha@aluno.demo.edu.br", "cpf": "100.000.004-42"},
            5: {"id": 5, "nome": "Elisa Cardoso Nunes", "email": "elisa.nunes@aluno.demo.edu.br", "cpf": "100.000.005-23"},
            6: {"id": 6, "nome": "Felipe Torres Gomes", "email": None, "cpf": "100.000.006-04"},
        },
        # matrícula = (aluno, turma); ativo=1/0
        "enturmacoes": [
            {"aluno": 1, "turma": 3, "ativo": 1}, {"aluno": 2, "turma": 3, "ativo": 1},
            {"aluno": 3, "turma": 4, "ativo": 1}, {"aluno": 4, "turma": 4, "ativo": 1},
            {"aluno": 5, "turma": 5, "ativo": 1}, {"aluno": 6, "turma": 5, "ativo": 1},
        ],
        "professores": {
            13: {"id": 13, "nome": "Prof. Marcos Vinícius Ribeiro", "email": "marcos.ribeiro@prof.demo.edu.br", "cpf": "200.000.001-08"},
            15: {"id": 15, "nome": "Profa. Helena Duarte", "email": "helena.duarte@prof.demo.edu.br", "cpf": "200.000.002-80"},
        },
        "vinculos": [
            {"id": 1, "servidor": 13, "turma": 3, "deletado": False},
            {"id": 2, "servidor": 13, "turma": 4, "deletado": False},
            {"id": 3, "servidor": 15, "turma": 5, "deletado": False},
        ],
    }


def dados_iniciais_moodle():
    return {
        "categorias": {},   # id -> {id,name,idnumber,parent}
        "cursos": {},       # id -> {id,fullname,shortname,idnumber,categoryid,visible}
        "usuarios": {},     # id -> {id,username,firstname,lastname,email,idnumber}
        "inscricoes": {},   # (curso_id, usuario_id) -> {roleid, suspended}
        "seq": {"categoria": 1, "curso": 1, "usuario": 2},  # ids iniciais parecidos com os de um Moodle recém-instalado
    }


# --------------------------------------------------------------------------------------------
# Estado compartilhado de cada "sistema" (dados + modo de falha)
# --------------------------------------------------------------------------------------------
class Sistema:
    def __init__(self, nome, fabrica):
        self.nome = nome
        self.fabrica = fabrica
        self.dados = fabrica()
        self.lock = threading.RLock()
        self.down_ate = 0.0          # 0 = normal; inf = derrubado até /up; senão timestamp
        self.modo = "503"

    def reset(self):
        with self.lock:
            self.dados = self.fabrica()

    def derrubado(self):
        with self.lock:
            return self.down_ate and time.time() < self.down_ate

    def derrubar(self, segundos, modo):
        with self.lock:
            self.modo = modo
            self.down_ate = float("inf") if segundos <= 0 else time.time() + segundos

    def subir(self):
        with self.lock:
            self.down_ate = 0.0


def log(sistema, msg):
    print(f"[{time.strftime('%H:%M:%S')}] [fake-{sistema}] {msg}", flush=True)


# --------------------------------------------------------------------------------------------
# Handler base (admin comum)
# --------------------------------------------------------------------------------------------
class BaseHandler(BaseHTTPRequestHandler):
    sistema: Sistema = None
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):  # silencia o log padrão; usamos o nosso
        pass

    # ---- util ----
    def enviar(self, status, corpo, content_type="application/json; charset=utf-8"):
        if not isinstance(corpo, (bytes, bytearray)):
            corpo = json.dumps(corpo, ensure_ascii=False).encode("utf-8") if not isinstance(corpo, str) else corpo.encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(corpo)))
        self.send_header("Connection", "close")
        self.end_headers()
        self.wfile.write(corpo)

    def corpo_bytes(self):
        n = int(self.headers.get("Content-Length") or 0)
        return self.rfile.read(n) if n else b""

    def falha_simulada(self):
        """Retorna True (e já respondeu) se o sistema está 'derrubado'."""
        s = self.sistema
        if not s.derrubado():
            return False
        if s.modo == "lento":
            log(s.nome, f"{self.command} {self.path} -> LENTO (dormindo 30s)")
            time.sleep(30)
            return False
        log(s.nome, f"{self.command} {self.path} -> 503 (sistema derrubado)")
        self.enviar(503, {"message": "Service Unavailable (fake derrubado)"})
        return True

    def admin_comum(self, caminho, q):
        s = self.sistema
        if caminho == "/__admin/estado":
            with s.lock:
                self.enviar(200, estado_publico(s))
        elif caminho == "/__admin/down":
            seg = int(q.get("seconds", ["0"])[0])
            modo = q.get("modo", ["503"])[0]
            s.derrubar(seg, modo)
            log(s.nome, f"DERRUBADO por {seg or 'tempo indeterminado'}s (modo={modo})")
            self.enviar(200, {"ok": True, "derrubado": True, "segundos": seg, "modo": modo})
        elif caminho == "/__admin/up":
            s.subir()
            log(s.nome, "VOLTOU ao normal")
            self.enviar(200, {"ok": True, "derrubado": False})
        elif caminho == "/__admin/reset":
            s.reset()
            s.subir()
            log(s.nome, "dados restaurados")
            self.enviar(200, {"ok": True})
        else:
            return False
        return True

    def do_GET(self):
        self.tratar()

    def do_POST(self):
        self.tratar()

    def tratar(self):
        u = urlparse(self.path)
        q = parse_qs(u.query, keep_blank_values=True)
        if u.path.startswith("/__admin/"):
            if not self.admin_comum(u.path, q) and not self.admin_especifico(u.path, q):
                self.enviar(404, {"erro": "admin desconhecido", "path": u.path})
            return
        if self.falha_simulada():
            return
        try:
            self.rotear(u.path, q)
        except Exception as e:  # noqa
            log(self.sistema.nome, f"ERRO interno: {e!r}")
            self.enviar(500, {"message": f"erro interno do fake: {e!r}"})

    def admin_especifico(self, caminho, q):
        return False

    def rotear(self, caminho, q):
        raise NotImplementedError


def estado_publico(s):
    d = s.dados
    saida = {"sistema": s.nome, "derrubado": bool(s.derrubado()), "modo": s.modo}
    if s.nome == "ieducar":
        saida.update({
            "turmas": list(d["turmas"].values()),
            "enturmacoes": d["enturmacoes"],
            "vinculos": d["vinculos"],
            "alunos": len(d["alunos"]),
            "professores": len(d["professores"]),
        })
    else:
        saida.update({
            "categorias": list(d["categorias"].values()),
            "cursos": list(d["cursos"].values()),
            "usuarios": list(d["usuarios"].values()),
            "inscricoes": [{"curso": c, "usuario": u, **v} for (c, u), v in d["inscricoes"].items()],
        })
    return saida


# --------------------------------------------------------------------------------------------
# iEducar fake
# --------------------------------------------------------------------------------------------
class IEducarHandler(BaseHandler):
    token = "ieducar-integracao-token-trocar-em-producao-0000"
    access_key = "ieducar-access-key"
    secret_key = "ieducar-secret-key"
    porta_publica = 80

    def rotear(self, caminho, q):
        s = self.sistema
        p = lambda k, d=None: q.get(k, [d])[0]  # noqa

        if caminho == "/api/version":
            return self.enviar(200, {"version": "2.12.0-fake"})

        if caminho.startswith("/module/Api/Servidor"):
            return self.legado(q)

        # API REST: exige Bearer
        auth = self.headers.get("Authorization", "")
        if auth != f"Bearer {self.token}":
            log(s.nome, f"{self.command} {self.path} -> 401 (token inválido)")
            return self.enviar(401, {"message": "Unauthenticated."})

        show = max(1, int(p("show", "15")))
        pagina = max(1, int(p("page", "1")))

        if caminho == "/api/school-class":
            with s.lock:
                itens = [self.turma_json(t) for t in sorted(s.dados["turmas"].values(), key=lambda t: t["id"])]
            return self.paginar(caminho, q, itens, show, pagina)

        if caminho == "/api/registration":
            ano = int(p("year_eq", str(ANO)))
            with s.lock:
                itens = self.matriculas_json(ano)
            return self.paginar(caminho, q, itens, show, pagina)

        self.enviar(404, {"message": "rota não existe no fake"})

    # ---- formatos ----
    def turma_json(self, t):
        d = self.sistema.dados
        c = d["cursos"][t["curso_id"]]
        e = d["escola"]
        return {
            "cod_turma": t["id"], "nm_turma": t["nome"], "sgl_turma": t["sigla"], "ano": t["ano"],
            "ativo": t["ativo"], "visivel": bool(t["ativo"]),
            "ref_cod_curso": c["id"], "ref_ref_cod_escola": e["id"], "ref_ref_cod_serie": t["serie_id"],
            "course": {"id": c["id"], "name": c["nome"], "ativo": c["ativo"]},
            "school": {"id": e["id"], "sigla": e["sigla"], "ativo": 1,
                       "person": {"id": e["pessoa_id"], "name": e["nome"]}},
        }

    def matriculas_json(self, ano):
        d = self.sistema.dados
        por_aluno = {}
        for en in d["enturmacoes"]:
            t = d["turmas"].get(en["turma"])
            if not t or t["ano"] != ano:
                continue
            por_aluno.setdefault(en["aluno"], []).append((en, t))
        saida = []
        for aluno_id in sorted(por_aluno):
            a = d["alunos"][aluno_id]
            pares = por_aluno[aluno_id]
            t0 = pares[0][1]
            saida.append({
                "id": aluno_id, "student_id": aluno_id, "nome": a["nome"], "email": a["email"],
                "aprovado": 3, "ativo": 1, "ano": ano,
                "ref_ref_cod_escola": d["escola"]["id"], "ref_cod_curso": t0["curso_id"],
                "student": {"id": aluno_id, "individual": {"idpes": 100 + aluno_id, "cpf": a["cpf"]}},
                "enrollments": [{"ref_cod_matricula": aluno_id, "ref_cod_turma": en["turma"], "sequencial": 1,
                                 "ativo": en["ativo"], "data_enturmacao": "2026-10-25 00:00:00"}
                                for en, _ in pares],
            })
        return saida

    def paginar(self, caminho, q, itens, show, pagina):
        total = len(itens)
        ultima = max(1, -(-total // show))
        fatia = itens[(pagina - 1) * show: pagina * show]

        def url(n):
            partes = {k: v[0] for k, v in q.items() if k != "page"}
            partes["page"] = str(n)
            return f"http://ieducar{caminho}?" + "&".join(f"{k}={v}" for k, v in partes.items())

        corpo = {
            "data": fatia,
            "links": {"first": url(1), "last": url(ultima), "prev": url(pagina - 1) if pagina > 1 else None,
                      "next": url(pagina + 1) if pagina < ultima else None},
            "meta": {"current_page": pagina, "from": (pagina - 1) * show + 1 if fatia else None,
                     "last_page": ultima, "per_page": show, "to": (pagina - 1) * show + len(fatia) if fatia else None,
                     "total": total},
        }
        log("ieducar", f"{self.command} {caminho} página {pagina}/{ultima} ({len(fatia)} itens)")
        self.enviar(200, corpo)

    # ---- API legada (HTTP 200 mesmo em erro) ----
    def legado(self, q):
        s = self.sistema
        p = lambda k, d=None: q.get(k, [d])[0]  # noqa
        if p("access_key") != self.access_key or p("secret_key") != self.secret_key:
            log(s.nome, "legado -> chave inválida (HTTP 200 com any_error_msg)")
            return self.enviar(200, {"msgs": [{"msg": "Chave de acesso inválida", "type": "error"}],
                                     "any_error_msg": True, "result": None, "oper": p("oper"),
                                     "resource": p("resource")})
        recurso = p("resource")
        with s.lock:
            d = s.dados
            if recurso == "servidores-disciplinas-turmas":
                ano = int(p("ano", str(ANO)))
                escolas = {int(x) for x in (p("escola", "") or "").split(",") if x}
                vinculos = []
                for v in d["vinculos"]:
                    t = d["turmas"].get(v["turma"])
                    if not t or t["ano"] != ano or d["escola"]["id"] not in escolas:
                        continue
                    vinculos.append({
                        "id": v["id"], "servidor_id": v["servidor"], "turma_id": v["turma"], "turno_id": 1,
                        "permite_lancar_faltas_componente": 0,
                        "disciplinas": [{"id": t["serie_id"], "serie_id": t["serie_id"], "tipo_nota": 1}],
                        "disciplinas_serie": {str(t["serie_id"]): [t["serie_id"]]},
                        "updated_at": "2026-10-25 15:59:12.694885",
                        "deleted_at": "2026-11-25 10:00:00.000000" if v["deletado"] else None,
                    })
                log("ieducar", f"legado servidores-disciplinas-turmas ano={ano} -> {len(vinculos)} vínculos")
                return self.enviar(200, {"vinculos": vinculos, "oper": "get", "resource": recurso,
                                         "msgs": [], "any_error_msg": False})
            if recurso == "dados-servidor":
                sid = int(p("servidor_id", "0"))
                prof = d["professores"].get(sid)
                if not prof:
                    return self.enviar(200, {"msgs": [{"msg": "Servidor não encontrado", "type": "error"}],
                                             "any_error_msg": True, "result": None, "oper": "get",
                                             "resource": recurso})
                log("ieducar", f"legado dados-servidor {sid}")
                return self.enviar(200, {"result": {
                    "servidor_id": sid, "nome": prof["nome"], "cpf": prof["cpf"], "ativo": 1,
                    "updated_at": "2026-10-25 15:59:12.694885", "inep": None, "email": prof["email"]},
                    "oper": "get", "resource": recurso, "msgs": [], "any_error_msg": False})
        self.enviar(200, {"msgs": [{"msg": f"recurso desconhecido: {recurso}", "type": "error"}],
                          "any_error_msg": True, "result": None})

    # ---- admin específico: simula mudanças feitas por humanos no iEducar ----
    def admin_especifico(self, caminho, q):
        s = self.sistema
        p = lambda k, d=None: q.get(k, [d])[0]  # noqa
        with s.lock:
            d = s.dados
            if caminho == "/__admin/renomear-turma":
                t = d["turmas"].get(int(p("id")))
                if not t:
                    return self.enviar(404, {"erro": "turma não existe"}) or True
                t["nome"] = p("nome")
                log("ieducar", f"ADMIN: turma {t['id']} renomeada para '{t['nome']}'")
                return self.enviar(200, {"ok": True, "turma": t}) or True
            if caminho in ("/__admin/inativar-turma", "/__admin/ativar-turma"):
                t = d["turmas"].get(int(p("id")))
                if not t:
                    return self.enviar(404, {"erro": "turma não existe"}) or True
                t["ativo"] = 0 if caminho.endswith("inativar-turma") else 1
                log("ieducar", f"ADMIN: turma {t['id']} ativo={t['ativo']}")
                return self.enviar(200, {"ok": True, "turma": t}) or True
            if caminho == "/__admin/adicionar-aluno":
                turma = int(p("turma"))
                if turma not in d["turmas"]:
                    return self.enviar(404, {"erro": "turma não existe"}) or True
                novo = max(d["alunos"]) + 1
                d["alunos"][novo] = {"id": novo, "nome": p("nome", f"Aluno {novo}"), "email": p("email") or None,
                                     "cpf": f"100.000.{novo:03d}-00"}
                d["enturmacoes"].append({"aluno": novo, "turma": turma, "ativo": 1})
                log("ieducar", f"ADMIN: aluno {novo} '{d['alunos'][novo]['nome']}' matriculado na turma {turma}")
                return self.enviar(200, {"ok": True, "aluno": d["alunos"][novo]}) or True
            if caminho == "/__admin/cancelar-matricula":
                aluno, turma = int(p("aluno")), int(p("turma"))
                achou = False
                for en in d["enturmacoes"]:
                    if en["aluno"] == aluno and en["turma"] == turma:
                        en["ativo"] = 0
                        achou = True
                log("ieducar", f"ADMIN: matrícula do aluno {aluno} na turma {turma} cancelada (achou={achou})")
                return self.enviar(200 if achou else 404, {"ok": achou}) or True
            if caminho == "/__admin/adicionar-professor":
                turma = int(p("turma"))
                if turma not in d["turmas"]:
                    return self.enviar(404, {"erro": "turma não existe"}) or True
                novo = max(d["professores"]) + 2
                d["professores"][novo] = {"id": novo, "nome": p("nome", f"Prof. {novo}"), "email": p("email") or None,
                                          "cpf": f"200.000.{novo:03d}-00"}
                d["vinculos"].append({"id": max(v["id"] for v in d["vinculos"]) + 1, "servidor": novo,
                                      "turma": turma, "deletado": False})
                log("ieducar", f"ADMIN: professor {novo} vinculado à turma {turma}")
                return self.enviar(200, {"ok": True, "professor": d["professores"][novo]}) or True
            if caminho == "/__admin/remover-vinculo":
                servidor, turma = int(p("servidor")), int(p("turma"))
                achou = False
                for v in d["vinculos"]:
                    if v["servidor"] == servidor and v["turma"] == turma:
                        v["deletado"] = True
                        achou = True
                log("ieducar", f"ADMIN: vínculo do professor {servidor} com a turma {turma} removido (achou={achou})")
                return self.enviar(200 if achou else 404, {"ok": achou}) or True
            if caminho == "/__admin/nova-turma":
                curso = int(p("curso", "1"))
                novo = max(d["turmas"]) + 1
                d["turmas"][novo] = {"id": novo, "nome": p("nome", f"Turma {novo}"), "sigla": "N", "ano": ANO,
                                     "ativo": 1, "curso_id": curso, "serie_id": 3}
                log("ieducar", f"ADMIN: nova turma {novo} no curso {curso}")
                return self.enviar(200, {"ok": True, "turma": d["turmas"][novo]}) or True
        return False


# --------------------------------------------------------------------------------------------
# Moodle fake (Web Services REST)
# --------------------------------------------------------------------------------------------
class MoodleHandler(BaseHandler):
    token = "a1b2c3d4e5f60718293a4b5c6d7e8f90"

    def rotear(self, caminho, q):
        if caminho in ("/login/index.php", "/"):
            return self.enviar(200, "<html><body>Moodle fake</body></html>", "text/html; charset=utf-8")
        if caminho != "/webservice/rest/server.php":
            return self.enviar(404, "not found", "text/plain")

        corpo = self.corpo_bytes().decode("utf-8")
        form = {k: v[0] for k, v in parse_qs(corpo, keep_blank_values=True).items()}
        for k, v in q.items():  # também aceita parâmetros na query
            form.setdefault(k, v[0])
        funcao = form.get("wsfunction", "")
        if form.get("wstoken") != self.token:
            log("moodle", f"{funcao} -> invalidtoken")
            return self.enviar(200, erro_moodle("invalidtoken", "Invalid token - token not found"))
        s = self.sistema
        with s.lock:
            try:
                resultado = self.executar(funcao, form, s.dados)
            except ErroMoodle as e:
                log("moodle", f"{funcao} -> ERRO {e.codigo}: {e.mensagem}")
                return self.enviar(200, erro_moodle(e.codigo, e.mensagem))
        log("moodle", f"{funcao} -> ok")
        self.enviar(200, "null" if resultado is None else resultado)

    # ---- funções WS ----
    def executar(self, f, p, d):
        if f == "core_course_get_categories":
            if p.get("criteria[0][key]") != "idnumber":
                return list(d["categorias"].values())
            return [self.cat_json(c) for c in d["categorias"].values() if c["idnumber"] == p.get("criteria[0][value]")]

        if f == "core_course_create_categories":
            nome, idn = p["categories[0][name]"], p.get("categories[0][idnumber]", "")
            if idn and any(c["idnumber"] == idn for c in d["categorias"].values()):
                raise ErroMoodle("invalidparameter", "Invalid parameter value detected")
            d["seq"]["categoria"] += 1
            cid = d["seq"]["categoria"]
            d["categorias"][cid] = {"id": cid, "name": nome, "idnumber": idn,
                                    "parent": int(p.get("categories[0][parent]", "0") or 0)}
            return [{"id": cid, "name": nome}]

        if f == "core_course_get_courses_by_field":
            if p.get("field") != "idnumber":
                raise ErroMoodle("invalidparameter", "Campo não suportado pelo fake")
            achados = [self.curso_json(c, d) for c in d["cursos"].values() if c["idnumber"] == p.get("value")]
            return {"courses": achados, "warnings": []}

        if f == "core_course_create_courses":
            fullname, short = p["courses[0][fullname]"], p["courses[0][shortname]"]
            idn = p.get("courses[0][idnumber]", "")
            if any(c["shortname"] == short for c in d["cursos"].values()):
                raise ErroMoodle("shortnametaken", f"O nome curto \"{short}\" já está em uso")
            if idn and any(c["idnumber"] == idn for c in d["cursos"].values()):
                raise ErroMoodle("courseidnumbertaken", "ID number já em uso")
            cat = int(p["courses[0][categoryid]"])
            if cat not in d["categorias"]:
                raise ErroMoodle("invalidparameter", "Categoria inexistente")
            d["seq"]["curso"] += 1
            cid = d["seq"]["curso"]
            d["cursos"][cid] = {"id": cid, "fullname": fullname, "shortname": short, "idnumber": idn,
                                "categoryid": cat, "visible": int(p.get("courses[0][visible]", "1"))}
            return [{"id": cid, "shortname": short}]

        if f == "core_course_update_courses":
            c = d["cursos"].get(int(p["courses[0][id]"]))
            if not c:
                raise ErroMoodle("invalidparameter", "Curso inexistente")
            if "courses[0][fullname]" in p:
                c["fullname"] = p["courses[0][fullname]"]
            if "courses[0][visible]" in p:
                c["visible"] = int(p["courses[0][visible]"])
            return {"warnings": []}

        if f == "core_user_get_users_by_field":
            if p.get("field") != "idnumber":
                raise ErroMoodle("invalidparameter", "Campo não suportado pelo fake")
            return [self.user_json(u) for u in d["usuarios"].values() if u["idnumber"] == p.get("values[0]")]

        if f == "core_user_create_users":
            u = {"username": p["users[0][username]"], "firstname": p["users[0][firstname]"],
                 "lastname": p["users[0][lastname]"], "email": p["users[0][email]"],
                 "idnumber": p.get("users[0][idnumber]", "")}
            if "@" not in u["email"]:
                raise ErroMoodle("invalidparameter", "Invalid parameter value detected")
            if any(x["username"] == u["username"] or (u["idnumber"] and x["idnumber"] == u["idnumber"])
                   for x in d["usuarios"].values()):
                raise ErroMoodle("invalidparameter", "Invalid parameter value detected")
            d["seq"]["usuario"] += 1
            u["id"] = d["seq"]["usuario"]
            d["usuarios"][u["id"]] = u
            return [{"id": u["id"], "username": u["username"]}]

        if f == "core_user_update_users":
            u = d["usuarios"].get(int(p["users[0][id]"]))
            if not u:
                raise ErroMoodle("invalidparameter", "Usuário inexistente")
            for campo in ("firstname", "lastname", "email"):
                if f"users[0][{campo}]" in p:
                    u[campo] = p[f"users[0][{campo}]"]
            return None

        if f == "core_enrol_get_enrolled_users":
            cid = int(p["courseid"])
            so_ativos = p.get("options[0][name]") == "onlyactive" and p.get("options[0][value]") == "1"
            if cid not in d["cursos"]:
                raise ErroMoodle("invalidrecord", "Can't find data record in database table course.")
            saida = []
            for (c, uid), v in d["inscricoes"].items():
                if c == cid and not (so_ativos and v["suspended"]):
                    saida.append({**self.user_json(d["usuarios"][uid]),
                                  "roles": [{"roleid": v["roleid"]}]})
            return saida

        if f == "enrol_manual_enrol_users":
            cid, uid = int(p["enrolments[0][courseid]"]), int(p["enrolments[0][userid]"])
            if cid not in d["cursos"] or uid not in d["usuarios"]:
                raise ErroMoodle("invalidparameter", "Curso ou usuário inexistente")
            d["inscricoes"][(cid, uid)] = {"roleid": int(p["enrolments[0][roleid]"]),
                                           "suspended": p.get("enrolments[0][suspend]", "0") == "1"}
            return None

        raise ErroMoodle("invalidparameter", f"Função não implementada no fake: {f}")

    @staticmethod
    def cat_json(c):
        return {"id": c["id"], "name": c["name"], "idnumber": c["idnumber"], "description": "",
                "descriptionformat": 1, "parent": c["parent"], "sortorder": 10000 * c["id"], "coursecount": 0,
                "visible": 1, "visibleold": 1, "timemodified": int(time.time()), "depth": 1,
                "path": f"/{c['id']}", "theme": ""}

    @staticmethod
    def curso_json(c, d):
        cat = d["categorias"].get(c["categoryid"], {})
        return {"id": c["id"], "fullname": c["fullname"], "displayname": c["fullname"], "shortname": c["shortname"],
                "categoryid": c["categoryid"], "categoryname": cat.get("name", ""), "idnumber": c["idnumber"],
                "format": "topics", "visible": c["visible"], "timecreated": int(time.time()),
                "timemodified": int(time.time())}

    @staticmethod
    def user_json(u):
        return {"id": u["id"], "username": u["username"], "firstname": u["firstname"], "lastname": u["lastname"],
                "fullname": f"{u['firstname']} {u['lastname']}", "email": u["email"], "idnumber": u["idnumber"],
                "auth": "manual", "suspended": False, "confirmed": True}


class ErroMoodle(Exception):
    def __init__(self, codigo, mensagem):
        super().__init__(mensagem)
        self.codigo, self.mensagem = codigo, mensagem


def erro_moodle(codigo, mensagem):
    return {"exception": "core\\exception\\moodle_exception", "errorcode": codigo, "message": mensagem}


# --------------------------------------------------------------------------------------------
def servir(handler_cls, sistema, porta):
    handler = type(handler_cls.__name__ + "Ligado", (handler_cls,), {"sistema": sistema})
    srv = ThreadingHTTPServer(("0.0.0.0", porta), handler)
    srv.daemon_threads = True
    log(sistema.nome, f"escutando na porta {porta}")
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    return srv


def main():
    ap = argparse.ArgumentParser(description="Fakes do iEducar e do Moodle")
    ap.add_argument("--ieducar-port", type=int, default=80)
    ap.add_argument("--moodle-port", type=int, default=8080)
    ap.add_argument("--so", choices=["ieducar", "moodle", "ambos"], default="ambos",
                    help="sobe só um dos fakes (útil quando cada um roda em um container)")
    ap.add_argument("--ieducar-token", default=IEducarHandler.token)
    ap.add_argument("--moodle-token", default=MoodleHandler.token)
    a = ap.parse_args()
    IEducarHandler.token = a.ieducar_token
    MoodleHandler.token = a.moodle_token
    if a.so in ("ieducar", "ambos"):
        servir(IEducarHandler, Sistema("ieducar", dados_iniciais_ieducar), a.ieducar_port)
    if a.so in ("moodle", "ambos"):
        servir(MoodleHandler, Sistema("moodle", dados_iniciais_moodle), a.moodle_port)
    try:
        while True:
            time.sleep(3600)
    except KeyboardInterrupt:
        sys.exit(0)


if __name__ == "__main__":
    main()
