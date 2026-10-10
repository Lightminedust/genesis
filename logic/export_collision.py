r"""Export a newly calculated collision as a real client frame for visual review.

Example (from logic):
  venv\Scripts\python.exe export_collision.py --a 6:12 --b 8:16 --energy 20000
"""
import argparse
import json
from pathlib import Path

from engine import PROTOCOL_VERSION, fill_collision
from fusion_dynamics import FusionExperiment
import genesis_pb2 as pb


def nuclide(text):
    try:
        z, a = (int(value) for value in text.split(':'))
        return z * 1000 + a
    except ValueError as error:
        raise argparse.ArgumentTypeError('Expected Z:A, for example 6:12.') from error


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--a', type=nuclide, default=1002)
    parser.add_argument('--b', type=nuclide, default=1003)
    parser.add_argument('--energy', type=float, default=64, help='Centre-of-mass beam energy in keV')
    parser.add_argument('--impact', type=float, default=0, help='Impact parameter in fm')
    parser.add_argument('--field', type=float, default=5.3, help='External magnetic field in T')
    parser.add_argument('--seed', type=int, default=7)
    parser.add_argument('--ready', action='store_true', help='Export selection before firing')
    parser.add_argument('--force', action='store_true', help='Show a fusion (capture kept, true probability reported)')
    parser.add_argument('--out', type=Path, default=Path('../artifacts/collision-run.pb'))
    args = parser.parse_args()
    experiment = FusionExperiment(args.seed)
    experiment.set_nucleus('a', args.a)
    experiment.set_nucleus('b', args.b)
    experiment.set_energy(args.energy)
    experiment.set_impact(args.impact)
    experiment.set_field(args.field)
    if not args.ready:
        experiment.fire(args.force)
    if experiment.error:
        parser.error(experiment.error)
    frame = pb.UniverseFrame(protocol_version=PROTOCOL_VERSION)
    fill_collision(frame.reactor, experiment)
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_bytes(frame.SerializeToString())
    metadata = {'seed': args.seed, 'a': args.a, 'b': args.b, 'energy_kev': args.energy,
                'impact_fm': args.impact, 'field_t': args.field, 'bytes': frame.ByteSize()}
    if frame.reactor.HasField('collision'):
        run = frame.reactor.collision
        phase_times = {}
        for sample in run.samples:
            phase_times.setdefault(sample.phase, [sample.time_s, sample.time_s])[1] = sample.time_s
        metadata.update(reaction=run.reaction, outcome=run.outcome, model=run.model,
                        resolved=run.outcome_resolved, energy_error=run.energy_error,
                        phase_times=phase_times, samples=len(run.samples))
    args.out.with_suffix('.json').write_text(json.dumps(metadata, indent=2, ensure_ascii=False), encoding='utf-8')
    print(json.dumps(metadata, ensure_ascii=True))


if __name__ == '__main__':
    main()
