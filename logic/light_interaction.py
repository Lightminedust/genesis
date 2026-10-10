"""Interaction faible d'un photon avec un atome immobile, au centre du faisceau.

Faisceau gaussien de rayon w (intensité exp(-2r²/w²)), aire πw²/2.
P = σ / aire : approximation de faible couplage, sans saturation ni Doppler.
Profil naturel lorentzien : FWHM en Hz = (Γ_haut + Γ_bas)/(2π).
Les longueurs d'onde du reste de l'app sont converties au vide pour les calculs.

Références : NIST, Spectral Line Shapes ; Einstein A/B (atomes non polarisés) ;
Nahar 2021, Atoms 9, 73, photoionisation hydrogénoïde 1s.
"""

import math

from scipy import constants as sc

import spectra

BEAM_WAIST_M = 1e-6


def beam_waist_m(wavelength_nm):
    """Au moins 1 µm et deux longueurs d'onde : faisceau faiblement focalisé."""
    return max(BEAM_WAIST_M, 2 * spectra.reported_to_vacuum_nm(wavelength_nm) * 1e-9)


def frequency_hz(wavelength_nm):
    return sc.c / (spectra.reported_to_vacuum_nm(wavelength_nm) * 1e-9)


def resonance_cross_section_m2(wavelength_nm, line_nm, a_ul, upper_rate,
                               lower_rate=0.0, degeneracy_ratio=3.0):
    """Section efficace E1, moyennée sur m et polarisation, régime faible."""
    gamma = upper_rate + lower_rate
    if gamma <= 0 or a_ul <= 0:
        return 0.0
    omega_detuning = 2 * math.pi * (frequency_hz(wavelength_nm) - frequency_hz(line_nm))
    lorentz = 1 / (1 + (2 * omega_detuning / gamma) ** 2)
    vacuum_m = spectra.reported_to_vacuum_nm(line_nm) * 1e-9
    return degeneracy_ratio * vacuum_m ** 2 / (2 * math.pi) * a_ul / gamma * lorentz


def hydrogen_1s_photoionization_m2(z, energy_ev, threshold_ev, reduced_mass=1.0):
    """Continuum coulombien non relativiste 1s ; σ(seuil H) ≈ 6,30e-22 m²."""
    if energy_ev < threshold_ev:
        return 0.0
    epsilon = math.sqrt(max(0.0, energy_ev / threshold_ev - 1))
    a0 = sc.physical_constants["Bohr radius"][0]
    threshold_sigma = 2 ** 9 * math.pi ** 2 / (3 * math.exp(4)) * sc.fine_structure * a0 ** 2
    threshold_sigma /= (z * reduced_mass) ** 2
    if epsilon < 1e-8:
        return threshold_sigma
    exponent = 4 - 4 * math.atan(epsilon) / epsilon
    denominator = -math.expm1(-2 * math.pi / epsilon)
    return threshold_sigma * (threshold_ev / energy_ev) ** 4 * math.exp(exponent) / denominator


def interaction_probability(sigma_m2, waist_m=BEAM_WAIST_M):
    """Probabilité pour un photon gaussien ; refus des géométries hors régime faible."""
    if not math.isfinite(waist_m) or waist_m <= 0:
        raise ValueError("Rayon du faisceau invalide")
    probability = sigma_m2 / (math.pi * waist_m ** 2 / 2)
    if not 0 <= probability <= 0.1:
        raise ValueError("Le couplage dépasse le domaine du modèle faible (P ≤ 0,1)")
    return probability
