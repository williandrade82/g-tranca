package com.gtranca.ui.persona

import androidx.annotation.StringRes
import com.gtranca.R
import com.gtranca.game.Gender
import com.gtranca.game.Profession
import com.gtranca.game.SeatRole

/** Nome da profissão no gênero do perfil (médico/médica). */
@StringRes
fun Profession.labelRes(gender: Gender): Int {
    val m = gender == Gender.MALE
    return when (this) {
        Profession.DOCTOR -> if (m) R.string.profession_doctor_m else R.string.profession_doctor_f
        Profession.CHEF -> if (m) R.string.profession_chef_m else R.string.profession_chef_f
        Profession.ENGINEER -> if (m) R.string.profession_engineer_m else R.string.profession_engineer_f
        Profession.TEACHER -> if (m) R.string.profession_teacher_m else R.string.profession_teacher_f
        Profession.FIREFIGHTER -> if (m) R.string.profession_firefighter_m else R.string.profession_firefighter_f
        Profession.SCIENTIST -> R.string.profession_scientist
        Profession.PILOT -> R.string.profession_pilot
        Profession.ARTIST -> if (m) R.string.profession_artist_m else R.string.profession_artist_f
        Profession.NURSE -> if (m) R.string.profession_nurse_m else R.string.profession_nurse_f
        Profession.POLICE -> R.string.profession_police
        Profession.FARMER -> if (m) R.string.profession_farmer_m else R.string.profession_farmer_f
        Profession.MECHANIC -> if (m) R.string.profession_mechanic_m else R.string.profession_mechanic_f
        Profession.MUSICIAN -> if (m) R.string.profession_musician_m else R.string.profession_musician_f
        Profession.LAWYER -> if (m) R.string.profession_lawyer_m else R.string.profession_lawyer_f
        Profession.PHOTOGRAPHER -> if (m) R.string.profession_photographer_m else R.string.profession_photographer_f
        Profession.SAILOR -> if (m) R.string.profession_sailor_m else R.string.profession_sailor_f
    }
}

/** Papel do assento no gênero da persona (Parceira, Adversário à esquerda...). O próprio jogador é "Você". */
@StringRes
fun SeatRole.labelRes(gender: Gender): Int {
    val m = gender == Gender.MALE
    return when (this) {
        SeatRole.YOU -> R.string.side_you
        SeatRole.PARTNER -> if (m) R.string.role_partner_m else R.string.role_partner_f
        SeatRole.OPPONENT -> if (m) R.string.role_opponent_m else R.string.role_opponent_f
        SeatRole.LEFT_OPPONENT -> if (m) R.string.role_left_m else R.string.role_left_f
        SeatRole.RIGHT_OPPONENT -> if (m) R.string.role_right_m else R.string.role_right_f
    }
}
