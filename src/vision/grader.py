from .constants import GRADE_THRESHOLDS

class Grader:
    @staticmethod
    def get_grade(diameter: float) -> str:
        """根据平均直径返回等级"""
        if diameter > GRADE_THRESHOLDS['A']: return 'A'
        if diameter > GRADE_THRESHOLDS['B']: return 'B'
        if diameter > GRADE_THRESHOLDS['C']: return 'C'
        if diameter > GRADE_THRESHOLDS['D']: return 'D'
        if diameter > GRADE_THRESHOLDS['E']: return 'E'
        return 'F'
