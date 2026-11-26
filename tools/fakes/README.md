# Fakes do iEducar e do Moodle

`fake_sistemas.py` (Python 3, só biblioteca padrão) imita as APIs que o T1/T2 usam, com dados iniciais (2 cursos, 3 turmas,
6 alunos, 2 professores) e endpoints `/__admin/*` para mudar o "iEducar" ao vivo ou derrubá-lo.
Veja o cabeçalho do arquivo para a lista completa. Uso normal: `scripts/subir-com-fakes.sh` (ou `.ps1`).

Execução direta: `python fake_sistemas.py --ieducar-port 18080 --moodle-port 18081`
Exemplos: `curl "localhost:18080/__admin/renomear-turma?id=3&nome=Novo+Nome"`, `curl "localhost:18080/__admin/down?seconds=60"`.
