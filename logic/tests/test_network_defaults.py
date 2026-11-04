"""Les réglages réseau par défaut (ticket #2) : rien n'est joignable depuis le réseau sans qu'on l'ait voulu.

docker-compose.yml ne publie ses ports que sur 127.0.0.1, fixe la version de MongoDB et lui donne des comptes ; le
moteur écoute sur la boucle locale et refuse une autre adresse sans GENESIS_ALLOW_REMOTE=1 ; l'application
installée lance son moteur sur 127.0.0.1.
"""

import re
import unittest
from pathlib import Path

import engine

ROOT = Path(__file__).resolve().parents[2]


def compose_lines():
    return (ROOT / "docker-compose.yml").read_text(encoding="utf-8").splitlines()


class ComposeTest(unittest.TestCase):
    def test_published_ports_stay_on_this_machine(self):
        ports, inside = [], False
        for line in compose_lines():
            stripped = line.split("#", 1)[0].rstrip()
            if re.match(r"^\s+ports:\s*$", stripped):
                inside = True
                continue
            if inside and re.match(r"^\s+-\s", stripped):
                ports.append(stripped.split("-", 1)[1].strip().strip("\"'"))
                continue
            inside = False
        self.assertEqual(sorted(ports), ["127.0.0.1:27017:27017", "127.0.0.1:50051:50051"])

    def test_mongodb_is_pinned_and_requires_its_accounts(self):
        text = "\n".join(compose_lines())
        images = re.findall(r"^\s+image:\s*(\S+)", text, re.MULTILINE)
        mongo = [i for i in images if i.startswith("mongo")]
        self.assertEqual(len(mongo), 1)
        self.assertRegex(mongo[0], r"^mongo:\d+\.\d+\.\d+$", "une version choisie, pas « latest »")
        # Les mots de passe sont exigés (« :? » : docker compose refuse de démarrer sans eux).
        self.assertIn("MONGO_INITDB_ROOT_PASSWORD=${MONGO_ROOT_PASSWORD:?", text)
        self.assertIn("GENESIS_DB_PASSWORD=${GENESIS_DB_PASSWORD:?", text)
        self.assertRegex(text, r"MONGO_URI=mongodb://\$\{GENESIS_DB_USER:-genesis\}:\$\{GENESIS_DB_PASSWORD\}@db:")
        init = (ROOT / "db" / "mongo-init" / "genesis-user.js").read_text(encoding="utf-8")
        self.assertIn("role: 'readWrite', db: 'genesis_db'", init, "le compte du moteur ne voit que sa base")
        self.assertIn(".env", (ROOT / ".gitignore").read_text(encoding="utf-8").split())


class EngineAddressTest(unittest.TestCase):
    def test_the_engine_listens_on_this_machine_by_default(self):
        self.assertEqual(engine.grpc_address({}), "127.0.0.1:50051")
        self.assertEqual(engine.grpc_address({"GRPC_HOST": "::1", "GRPC_PORT": "51200"}), "::1:51200")

    def test_another_interface_needs_an_explicit_choice(self):
        for host in ("0.0.0.0", "[::]", "192.168.1.20"):
            with self.assertRaises(OSError):
                engine.grpc_address({"GRPC_HOST": host})
        self.assertEqual(engine.grpc_address({"GRPC_HOST": "0.0.0.0", "GENESIS_ALLOW_REMOTE": "1"}), "0.0.0.0:50051")

    def test_the_installed_application_keeps_its_engine_local(self):
        source = (ROOT / "sim/app/src/main/java/org/genesis/engine/BundledEngine.java").read_text(encoding="utf-8")
        self.assertIn('put("GRPC_HOST", "127.0.0.1")', source)
        self.assertIn('remove("MONGO_URI")', source)


if __name__ == "__main__":
    unittest.main()
