"""La chaîne de désintégration envoyée à la timeline : U-238 → … → Pb-206."""
import unittest

from engine import session_frame
from simulator.repository import ElementRepository
from simulator.simulation import Session

REPOSITORY = ElementRepository()


def chain_of(z):
    session = Session(REPOSITORY, seed=1)
    session.load_element(z)
    return session, session.decay_chain()


class DecayChainTest(unittest.TestCase):
    def test_uranium_ends_in_lead_206_through_the_known_series(self):
        _, chain = chain_of(92)
        names = [(link["z"], link["a"]) for link in chain]
        self.assertEqual((92, 238), names[0])
        self.assertEqual((82, 206), names[-1])
        self.assertTrue(chain[-1]["stable"])
        self.assertIn((88, 226), names)          # le radium de Marie Curie
        self.assertIn((86, 222), names)          # le radon
        # 8 α et 6 β⁻ : A perd 32, Z perd 10.
        modes = [link["mode"] for link in chain[:-1]]
        self.assertEqual(8, modes.count("alpha"))
        self.assertEqual(6, modes.count("beta-"))

    def test_each_step_follows_its_mode(self):
        _, chain = chain_of(92)
        shift = {"alpha": (-2, -4), "beta-": (1, 0), "beta+": (-1, 0), "CE": (-1, 0), "IT": (0, 0)}
        for here, there in zip(chain, chain[1:]):
            dz, da = shift[here["mode"]]
            self.assertEqual((here["z"] + dz, here["a"] + da), (there["z"], there["a"]))
            self.assertGreater(here["half_life_s"], 0)

    def test_a_stable_atom_has_a_single_stable_link(self):
        _, chain = chain_of(6)
        self.assertEqual(1, len(chain))
        self.assertTrue(chain[0]["stable"])

    def test_the_frame_carries_the_chain_with_names_and_readable_half_lives(self):
        session, _ = chain_of(92)
        frame = session_frame(1, session, session.evaluate("x"), REPOSITORY.source, [], "x", True)
        first = frame.nucleus.chain[0]
        self.assertEqual("U-238", first.nuclide)
        self.assertEqual("Uranium", first.element)
        self.assertIn("milliards", first.half_life)
        self.assertEqual("Pb-206", frame.nucleus.chain[-1].nuclide)


if __name__ == "__main__":
    unittest.main()
